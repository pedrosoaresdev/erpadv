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
| `users/{uid}` | perfil do usuário | `officeId`, `email`, `displayName`, `role`, `status` (`ACTIVE`/`BLOCKED`/`INVITED`), `permissionsGranted[]`, `permissionsRevoked[]`, `oabNumber`, `phone`, `clientId` (portal), `lastLoginAt`, `tokensRevokedAt` |
| `offices/{officeId}` | escritório | `name`, `cnpj`, `ownerUid`, `plan`, `status`, `settings{}` |
| `permissions/{code}` | catálogo de permissões | `module`, `description` (gerado a partir do enum do backend) |

## Subcoleções por escritório (`offices/{officeId}/…`)

| Coleção | Uso | Campos-chave |
|---|---|---|
| `roles` | papéis personalizados do escritório | `name`, `basedOn`, `permissions[]` |
| `clients` | CRM | `type` (`PF`/`PJ`), `name`, `nameLower`, `documentEnc`, `documentHash`, `documentMasked`, `rgEnc`, `birthDate`, `phone`, `whatsapp`, `email`, `address{}`, `profession`, `maritalStatus`, `notes`, `status`, `defaulting`, `searchTokens[]` |
| `clients/{id}/interactions` | histórico de atendimentos | `channel`, `subject`, `description`, `occurredAt`, `userUid`, `userName` |
| `cases` | processos | `cnjNumber`, `clientId`, `clientName`, `opposingParty`, `court`, `courtDivision`, `district`, `state`, `area`, `proceduralClass`, `subject`, `responsibleUid`, `responsibleName`, `claimValueCents`, `distributionDate`, `status`, `notes`, `confidential`, `searchTokens[]` |
| `case_movements` | movimentações/decisões/despachos | `caseId`, `type`, `date`, `title`, `description`, `source` (`MANUAL`/`INTEGRACAO`) |
| `deadlines` | prazos | `caseId`, `clientId`, `description`, `type`, `startDate`, `dueDate`, `responsibleUid`, `priority`, `status`, `alertsSent[]`, `validatedBy` |
| `hearings` | audiências | `caseId`, `type`, `dateTime`, `location`, `responsibleUid` |
| `appointments` | agenda | `type`, `title`, `start`, `end`, `responsibleUids[]`, `clientId`, `caseId`, `reminders[]` |
| `contracts` | contratos | `clientId`, `caseIds[]`, `type`, `status`, `templateId`, `signedDocumentId`, `history[]` |
| `fees` | honorários | `contractId`, `clientId`, `kind` (`FIXO`/`PARCELADO`/`ETAPAS`/`EXITO`), `amountCents`, `installments[]` |
| `financial_transactions` | lançamentos (receber/pagar) | `direction` (`IN`/`OUT`), `category`, `amountCents`, `dueDate`, `paidAt`, `status`, `feeId`, `receiptDocumentId`, `scope` (`ESCRITORIO`/`SOCIO`) |
| `expenses` | despesas recorrentes/cadastro | `category`, `supplier`, `amountCents`, `recurrence` |
| `documents` | metadados de arquivos | `name`, `category`, `clientId`, `caseId`, `storagePath`, `contentType`, `size`, `sha256`, `currentVersion`, `confidential`, `sharedWithClient`, `uploadedBy` |
| `document_versions` | versões | `documentId`, `version`, `storagePath`, `sha256`, `uploadedBy` |
| `document_access_logs` | quem leu/baixou | `documentId`, `uid`, `action`, `at`, `ip` |
| `tasks` | tarefas internas | `title`, `assigneeUid`, `dueDate`, `status`, `caseId` |
| `notifications` | notificações por usuário | `recipientUid`, `type`, `title`, `body`, `link`, `read` |
| `messages` | comunicação interna / portal | `threadId`, `fromUid`, `toUids[]`, `body`, `clientVisible` |
| `audit_logs` | trilha imutável | `seq`, `action`, `entityType`, `entityId`, `actorUid`, `actorEmail`, `ip`, `details{}`, `prevHash`, `hash`, `at` |
| `audit_meta/chain` | cabeça da cadeia | `lastSeq`, `lastHash` |
| `ai_interactions` | uso da IA | `agent`, `uid`, `promptHash`, `model`, `tokens`, `reviewedBy` |
| `system_settings` | configurações do escritório | `modules{}`, `notifications{}`, `integrations{}` |
| `device_tokens` | tokens FCM | `uid`, `token`, `platform` |

## Índices

Definidos em `firebase/firestore.indexes.json`. Cada consulta paginada do backend tem um
índice composto correspondente (ex.: `clients: status ASC, nameLower ASC` e
`clients: searchTokens CONTAINS, nameLower ASC`).

## Storage

```
offices/{officeId}/documents/{documentId}/v{versao}/{nomeSanitizado}
offices/{officeId}/clients/{clientId}/...
```
As Storage Rules negam leitura e escrita a qualquer cliente; os arquivos só trafegam pelo
backend (upload validado por tipo, tamanho e assinatura binária; download auditado).
