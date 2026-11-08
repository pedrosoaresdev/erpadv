# Modelagem do Cloud Firestore

Todas as coleções de negócio ficam sob o documento do escritório:
`offices/{officeId}/<coleção>/{id}`. Cada documento também guarda `officeId`
(redundância deliberada: facilita *collection group queries* administrativas, exportações e
auditoria). Campos comuns a todas as entidades:

| Campo | Tipo | Observação |
|---|---|---|
| `id` | string | igual ao ID do documento |
| `officeId` | string | escritório dono do dado |
| `createdAt` / `updatedAt` | number (epoch ms) | |
| `createdBy` / `updatedBy` | string (uid) | |

## Coleções globais

| Coleção | Documento | Principais campos |
|---|---|---|
| `users/{uid}` | perfil do usuário | `officeId`, `email`, `displayName`, `role` (papel-base), `customRoleId`/`customRoleName` (cargo do escritório), `status` (`ACTIVE`/`BLOCKED`/`INVITED`), `permissionsGranted[]`, `permissionsRevoked[]`, `oabNumber`, `cpfEnc`/`cpfHash`/`cpfMasked`, `phone`, `clientId` (portal), `lastLoginAt`, `tokensRevokedAt` |
| `offices/{officeId}` | escritório (tenant) | `name`, `nameLower`, `cnpj`, `ownerUid`, `plan` (`ESSENCIAL`/`PROFISSIONAL`/`ENTERPRISE`), `maxUsers`, `status` (`ACTIVE`/`SUSPENDED`), `suspendedAt`, `suspendedReason`, `createdByPlatform` |
| `permissions/{code}` | catálogo de permissões | `module`, `description` (gerado a partir do enum do backend) |

## Subcoleções por escritório (`offices/{officeId}/…`)

| Coleção | Uso | Campos-chave |
|---|---|---|
| `roles` | cargos personalizados (ex.: Advogado júnior) | `name`, `nameLower`, `description`, `color`, `baseRole`, `permissions[]` |
| `boards` | quadros Kanban | `name`, `color`, `visibility` (`OFFICE`/`MEMBERS`), `ownerUid`, `memberUids[]`, `columns[]` (`id`, `name`, `wipLimit`, `done`, `automation{notifyAssignees, addLabelId, assignUid, setDueInDays}`), `labels[]`, `archived` |
| `board_cards` | cartões | `boardId`, `columnId`, `position` (fracionária), `title`, `assigneeUids[]`, `labelIds[]`, `priority`, `startDate`, `dueDate`, `completed`, `completedAt`, `checklist[]`, `clientId`, `caseId`, `deadlineId`, `columnEnteredAt`, `commentCount`, `archived` |
| `card_activity` | comentários e eventos dos cartões | `cardId`, `boardId`, `kind` (`COMMENT`/`EVENT`), `text`, `mentionUids[]`, `actorUid`, `at` |
| `leads` | CRM comercial (funil) | `name`, `type`, `company`, `phone`, `email`, `source`, `legalArea`, `subject`, `stage` (`NOVO` novo contato, `CONTATO` primeiro atendimento, `QUALIFICADO`, `REUNIAO_AGENDADA`, `PROPOSTA`, `NEGOCIACAO` aguardando decisão, `CONTRATO_ENVIADO`, `GANHO` cliente convertido, `PERDIDO` atendimento encerrado), `estimatedValueCents`, `ownerUid`, `nextAction`, `nextActionDate`, `lostReason`, `tags[]`, `stageEnteredAt`, `lastActivityAt`, `wonAt`, `lostAt`, `convertedClientId` — sem CPF/CNPJ (coletado só na conversão) |
| `lead_activities` | interações com a oportunidade | `leadId`, `type`, `text`, `actorUid`, `at` |
| `clients` | CRM | `type` (`PF`/`PJ`), `name`, `nameLower`, `documentEnc`, `documentHash`, `documentMasked`, `rgEnc`, `birthDate`, `phone`, `whatsapp`, `email`, `address{}`, `profession`, `maritalStatus`, `nationality`, `stateRegistration`, `municipalRegistration`, `legalRepresentative`, `contacts[]`, `source`, `sourceDetail`, `lastActivityAt`, `notes`, `status`, `defaulting`, `searchTokens[]` |
| `clients/{id}/interactions` | histórico de atendimentos | `channel`, `subject`, `description`, `occurredAt`, `userUid`, `userName` |
| `cases` | processos (+ `integrationSyncedAt`, `integrationStatus`, `integrationError`, `integrationSource`) | `cnjNumber`, `clientId`, `clientName`, `opposingParty`, `court`, `courtDivision`, `district`, `state`, `area`, `proceduralClass`, `subject`, `responsibleUid`, `responsibleName`, `claimValueCents`, `distributionDate`, `status`, `notes`, `confidential`, `activeParty`, `passiveParty`, `instance`, `judgingBody`, `secondarySubjects[]`, `lawyerOab`, `freeJustice`, `phase`, `sentenceDate`, `finalJudgmentDate`, `responsibleHistory[]`, `lastMovementAt`, `searchTokens[]` |
| `case_movements` | movimentações/decisões/despachos | `caseId`, `type`, `date`, `title`, `description`, `source` (`MANUAL`/`DATAJUD`/`IMPORTACAO`), `externalId` (hash da movimentação no DataJud, evita duplicidade), `important` |
| `deadlines` | prazos | `caseId`, `caseNumber`, `clientId`, `clientName`, `description`, `type`, `startDate`, `dueDate`, `days`, `countingMode` (`UTEIS`/`CORRIDOS`/`MANUAL`), `court`, `responsibleUid`, `priority`, `status` (`PENDENTE`/`CONCLUIDO`/`CANCELADO`; *vencido* é derivado), `validated`, `validatedBy`, `alertsSent[]`, `restricted`, `origin` (`PUBLICACAO_DJE`/`INTIMACAO_ELETRONICA`/…), `availabilityDate`, `publicationDate`, `awarenessDate`, `receiptPath`/`receiptName`/`receiptContentType`/`receiptSize`/`receiptSha256`/`receiptUploadedAt` (comprovante de cumprimento) |
| `court_calendar` | feriados locais e suspensões | `startDate`, `endDate`, `court` (nulo = todos), `description`, `kind` (`FERIADO`/`SUSPENSAO`) |
| `appointments` | agenda (+ `googleEventIds{uid: id}` dos usuários conectados ao Google) (audiências = `type` `AUDIENCIA`/`JULGAMENTO`/`SUSTENTACAO_ORAL`/`PERICIA`) | `type`, `title`, `start`, `end` (epoch ms), `allDay`, `location`, `meetingUrl`, `responsibleUids[]`, `clientId`, `caseId`, `reminders[]` (minutos), `remindersSent[]`, `restricted`, `seriesId`/`seriesIndex`/`recurrence` (série recorrente), `attendance{uid: PENDENTE/CONFIRMADO/RECUSADO/TALVEZ}`, `guests[]` (`name`, `email`), `status` (`AGENDADO`/`REALIZADO`/`REDESIGNADO`/`ADIADO`/`CANCELADO`), `outcome`, `statusChangedAt`, `meetingPlatform` (`GOOGLE_MEET`/`ZOOM`/`TEAMS`/`PRESENCIAL`/`OUTRO`), `minutes` (ata), `minutesUpdatedAt`, `minutesByName` |
| `contracts` | contratos | `number` (CT-AAAA-NNNN), `title`, `type`, `status` (`RASCUNHO`/`ATIVO`/`ENCERRADO`/`CANCELADO`), `clientId`, `caseId`, `responsibleUid`, `object`, `startDate`, `endDate`, `feeModel` (`FIXO`/`PARCELADO`/`ETAPAS`/`EXITO`/`MENSAL`), `amountCents`, `installments`, `monthlyCents`, `months`, `stages[]`, `successPercentBp`, `body` (minuta), `templateId`, `signedDocumentId`, `expiryAlertsSent[]`, `renewals[]` (`renewedOn`, `previousEndDate`, `newEndDate`, `monthsAdded`, `monthlyCents`, `note`, `byName`) — histórico via `audit_logs` |
| `contract_templates` | modelos com campos `{{grupo.campo}}` | `name`, `type`, `body`, `active` (modelos `system:*` vêm do código) |
| `counters/contracts-AAAA` | numeração sequencial | `value` |
| `financial_transactions` | lançamentos (receber/pagar) | `direction` (`RECEITA`/`DESPESA`), `category`, `scope` (`ESCRITORIO`/`PESSOAL`), `ownerUid` (pessoal), `description`, `amountCents`, `dueDate`, `status` (`ABERTO`/`PAGO`/`CANCELADO`; *atrasado* é derivado), `paidDate`, `paidAmountCents`, `paymentMethod`, `clientId`, `caseId`, `counterparty`, `groupId`/`installmentNumber`/`installmentCount`/`recurrent`, `receiptPath`/`receiptSha256`, `costCenterId`/`costCenterName`, `bankAccountId`/`bankAccountName`, `receiptNumber` (recibo emitido) |
| `cost_centers` | centros de custo | `name`, `nameLower`, `code`, `description`, `active` |
| `bank_accounts` | contas bancárias/caixa | `name`, `bankName`, `bankCode`, `agency`, `accountNumber`, `kind`, `initialBalanceCents`, `initialBalanceDate`, `active` (saldo calculado pelas baixas) |
| `finance_collections` | cobranças registradas | `clientId`, `clientName`, `transactionIds[]`, `amountCents`, `channel`, `outcome`, `message`, `promisedDate`, `createdByName` |
| `commission_rules` | regras de comissão | `beneficiaryUid`, `beneficiaryName`, `percentBp`, `categories[]`, `clientId`, `caseId`, `responsibleOnly`, `active` |
| `commission_payouts` | comissões lançadas | `beneficiaryUid`, `periodFrom`, `periodTo`, `amountCents`, `receiptIds[]` (recebimentos já comissionados), `expenseId` |
| `lead_proposals` | propostas do CRM | `leadId`, `number`, `title`, `scope`, `feeTerms`, `valueCents`, `validUntil`, `status` (`RASCUNHO`/`ENVIADA`/`ACEITA`/`RECUSADA`), `sentAt`, `decidedAt`, `decisionNote` |
| `documents` | metadados de arquivos | `title`, `category`, `clientId`, `caseId`, `contractId`, `confidential`, `restricted`, `sharedWithClient`, `archived`, `currentVersion`, `fileName`, `contentType`, `size`, `sha256`, `storagePath`, `uploadedBy`, `searchTokens[]`, `folderId`, `tags[]`, `favoriteUids[]`, `deleted`/`deletedAt`/`deletedBy`/`deletedByName` (lixeira) |
| `document_folders` | pastas e subpastas da central | `name`, `nameLower`, `parentId` (nulo = raiz), `color` |
| `legal_documents` | Editor Jurídico | `title`, `titleLower`, `kind` (`PROCURACAO`, `PETICAO_INICIAL`, `CONTESTACAO`…), `templateId`, `templateName`, `clientId`, `clientName`, `caseId`, `caseNumber`, `restricted`, `lawyerUid`, `lawyerName`, `content` (HTML saneado), `header`, `footer`, `pageNumbers`, `letterheadId`, `status` (`RASCUNHO`/`FINAL`), `version`, `finalizedAt`, `finalizedByName`, `storedDocumentId`, `searchTokens[]` |
| `legal_document_versions` | histórico de versões do editor | `draftId`, `version`, `title`, `content`, `header`, `footer`, `pageNumbers`, `status`, `note`, `createdByName` |
| `letterheads` | papéis timbrados do Editor Jurídico | `name`, `imagePath`/`imageContentType` (PNG/JPEG A4 no Storage), `pdfPath` (original, opcional), `marginTop`/`marginBottom`/`marginLeft`/`marginRight` (mm), `primary` (padrão), `createdByName` |
| `legal_templates` | modelos do escritório (os do sistema vêm do código) | `name`, `nameLower`, `kind`, `description`, `content` (HTML com campos `{{grupo.campo}}`), `createdByName` |
| `document_versions` | versões | `documentId`, `version`, `storagePath`, `sha256`, `uploadedBy` |
| `document_access_logs` | quem enviou/visualizou/baixou/editou | `documentId`, `version`, `uid`, `userName`, `action`, `ip`, `createdAt` |
| Tarefas | módulo *Tarefas* | usa `boards`/`board_cards`: quadro padrão "Tarefas do escritório" (modelo `tarefas`) criado no primeiro uso; a lista reúne cartões de todos os quadros |
| `notifications` | notificações por usuário | `recipientUid`, `type`, `title`, `body`, `link`, `read` |
| `messages` | comunicação interna / portal | `threadId`, `fromUid`, `toUids[]`, `body`, `clientVisible` |
| `audit_logs` | trilha imutável | `seq`, `action`, `entityType`, `entityId`, `actorUid`, `actorEmail`, `ip`, `details{}`, `prevHash`, `hash`, `at` |
| `audit_meta/chain` | cabeça da cadeia | `lastSeq`, `lastHash` |
| `ai_interactions` | uso da IA | `agent`, `uid`, `promptHash`, `model`, `tokens`, `reviewedBy` |
| `system_settings` | configurações do escritório | `modules{}`, `notifications{}`, `integrations{}` |
| `device_tokens` | tokens FCM (ID = SHA-256 do token) | `uid`, `token`, `platform` |
| `integration_settings` | conectores (ID = provedor: `DATAJUD`, `DJEN`, `SMTP`, `WHATSAPP`, `GOOGLE_CALENDAR`, `ZAPSIGN`…) | `provider`, `enabled`, `autoSync`, `config{}` (somente campos não sigilosos por provedor), `secretsEnc{}` (AES-256-GCM, nunca devolvidos à interface), `lastRunAt`, `lastSuccessAt`, `lastError`, `lastErrorAt` |
| `integration_runs` | histórico das sincronizações | `provider`, `trigger` (`MANUAL`/`AUTOMATICO`), `status`, `startedAt`, `finishedAt`, `processed`, `created`, `updated`, `errors`, `message`, `details[]`, `triggeredByName` |
| `publications` | publicações do DJEN (ID = `djen-{id}`) | `source`, `externalId`, `hash`, `availableDate`, `court`, `kind`, `documentType`, `organ`, `className`, `text`, `caseNumber`, `caseNumberDigits`, `link`, `oab`, `lawyerUid`, `lawyerName`, `caseId`, `clientName`, `restricted`, `status` (`NOVA`/`LIDA`/`PRAZO_CRIADO`/`ARQUIVADA`), `deadlineId`, `readAt`, `readByName` |
| `communications` | central de comunicação | `channel` (`EMAIL`/`WHATSAPP`/`TELEFONE`/`PRESENCIAL`/`CARTA`/`INTERNO`), `direction` (`SAIDA`/`ENTRADA`), `status` (`ENVIADO`/`FALHOU`/`RECEBIDO`/`REGISTRADO`), `clientId`, `clientName`, `caseId`, `caseNumber`, `address`, `subject`, `body`, `error`, `attachments[]`, `externalId` (id da mensagem no provedor), `templateName`, `followUpDate`, `followUpDone`, `followUpNotified`, `createdByName`, `restricted` |
| `message_templates` | modelos de mensagem | `name`, `channel`, `subject`, `body` (campos `{{grupo.campo}}`), `whatsappTemplate` (nome do modelo aprovado na Meta), `createdByName` |
| `signature_requests` | assinatura eletrônica | `documentId`, `documentTitle`, `externalToken`, `status` (`ENVIADO`/`ASSINADO`/`RECUSADO`/`CANCELADO`), `signers[]` (`name`, `email`, `status`, `signUrl`, `signedAt`), `signedFileUrl`, `signedStored`, `signedVersion`, `sandbox`, `createdByName`, `completedAt` |
| `google_links` | conta Google de cada usuário (ID = uid) | `email`, `refreshTokenEnc`, `accessTokenEnc` (cifrados), `accessExpiresAt`, `connectedAt` |

Coleção global `system_locks/{job}`: trava distribuída dos jobs agendados (`owner`, `lockedUntil`).

Os jobs de alertas usam *collection group queries* em `deadlines` (`status`, `dueDate`) e
`appointments` (`start`), com índices de escopo `COLLECTION_GROUP` declarados em `firestore.indexes.json`.

## Índices

Definidos em `firebase/firestore.indexes.json`. Cada consulta paginada do backend tem um
índice composto correspondente (ex.: `clients: status ASC, nameLower ASC` e
`clients: searchTokens CONTAINS, nameLower ASC`).

## Storage

```
offices/{officeId}/documents/{documentId}/v{versao}/{nomeSanitizado}
offices/{officeId}/clients/{clientId}/...
offices/{officeId}/finance/{transactionId}/{uuid}-{nome}   (comprovantes)
```
As Storage Rules negam leitura e escrita a qualquer cliente; os arquivos só trafegam pelo
backend (upload validado por tipo, tamanho e assinatura binária; download auditado).
