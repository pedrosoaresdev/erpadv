# Segurança, sigilo profissional e LGPD

## Controles implementados

| Controle | Implementação |
|---|---|
| Autenticação | Firebase Authentication (e-mail/senha, verificação de e-mail, recuperação de senha). Backend valida todo ID token com o Admin SDK. |
| Autorização | RBAC com 11 papéis e 35 permissões granulares, mais cargos personalizados por escritório (ninguém cria ou atribui cargo com permissão que não possui; `PLATFORM_ADMIN` e `PORTAL_ACCESS` nunca entram em cargos); concessões/revogações por usuário; `@PreAuthorize` em todos os endpoints; front esconde o que não é permitido, mas o backend decide. |
| Isolamento multiempresa | Dados em `offices/{officeId}/…` + campo `officeId`; `officeId` vem do claim do token e é conferido com o perfil persistido (`PROFILE_MISMATCH`). Repositórios exigem `officeId` em toda operação. Teste E2E prova que o escritório B não lê dados do A. |
| Processos criminais/sigilosos | Flag `restricted`: invisíveis (404) para quem não tem `CRIMINAL_READ`; consulta auditada. |
| Security Rules | Firestore e Storage negam escrita do cliente; leitura só do próprio perfil e das próprias notificações. 15 testes automatizados no emulador. |
| Dados pessoais | CPF/CNPJ e RG cifrados com AES-256-GCM; busca por HMAC-SHA256; exibição mascarada para perfis sem `CLIENTS_SENSITIVE_READ`; visualização completa é auditada. |
| Auditoria imutável | Cadeia SHA-256 encadeada, gravação transacional com `create`, sem endpoints de alteração/exclusão; verificação de integridade em `/api/audit/verify`. |
| Painel da plataforma | Master definido somente por variável de ambiente (`PLATFORM_MASTER_EMAILS`), com e-mail verificado em produção; rotas `/api/platform/**` exigem `PLATFORM_ADMIN`; o master sem escritório não acessa dados de nenhum escritório. Criação de escritório/administrador, alteração de plano, suspensão e redefinição de senha ficam na auditoria do próprio escritório. |
| Escritório suspenso | Filtro de autenticação recusa toda a equipe com `OFFICE_SUSPENDED` (cache curto, invalidado na suspensão); dados preservados. |
| Senhas definidas pelo administrador | Política mínima (8+ caracteres com letras e números) no front e no back; gerador usa `crypto.getRandomValues` no navegador; definir senha encerra as sessões abertas do usuário. |
| Sessões | Bloqueio desabilita o usuário no Firebase, revoga refresh tokens e invalida tokens já emitidos (`tokensRevokedAt`). |
| Rate limiting | Nginx (`limit_req`, 20 r/s por IP; 5 r/s no onboarding) + filtro na API (300 req/min por usuário). |
| Transporte | HTTPS (TLS 1.2/1.3) no Nginx, HSTS, redirecionamento 80→443. |
| XSS | Angular escapa interpolação por padrão; CSP sem `unsafe-inline` para scripts (build sem *critical CSS inline*); API responde `default-src 'none'`. |
| CSRF | Não aplicável: API stateless com Bearer token em cabeçalho (sem cookies de sessão). |
| Validação | Bean Validation em todos os DTOs + validação de CPF/CNPJ/CNJ no front e no back; erros padronizados (RFC 7807) sem stack trace. |
| Credenciais | Nenhuma credencial administrativa no front ou nas imagens; service account via Docker secret; chaves de desenvolvimento recusadas com `APP_PRODUCTION=true`. |
| Logs | JSON estruturado com `traceId`, `uid`, `officeId`; logger `AUDIT` dedicado. |
| Arquivos | Upload só via backend; tipo detectado pela assinatura binária (PDF/DOCX/PNG/JPEG), limites de 10 MB (comprovantes) e 25 MB (documentos), nome sanitizado, SHA-256 por versão; conteúdo servido autenticado com `no-store`/`nosniff`/CSP `sandbox`; cada visualização e download registrados por documento e na auditoria; versões nunca apagadas (arquivamento em vez de exclusão). |
| Sigilo documental | Categorias Inquéritos/Sigilosos sempre sigilosas; documentos sigilosos exigem `DOCUMENTS_CONFIDENTIAL`, documentos de processo criminal exigem `CRIMINAL_READ`; sigilosos nunca liberados ao portal. |
| Contratos | Honorários de contrato ativo bloqueados para alteração; CPF/CNPJ na minuta apenas para quem tem acesso a dados sensíveis. |
| Separação patrimonial | Lançamentos pessoais de sócios visíveis apenas ao próprio sócio e excluídos de relatórios do escritório. |
| Exclusão com obrigação de guarda | Clientes só podem ser excluídos sem processos, contratos, financeiro, documentos, prazos ou compromissos (`CLIENTS_DELETE`); processos só depois de arquivados e sem vínculos (`CASES_DELETE`); motivo obrigatório e registro na auditoria. Nos demais casos, arquivamento. |
| Exportações e fichas | Exportação de listas exige `REPORTS_VIEW`; documentos sempre mascarados na lista; ficha com CPF/CNPJ completo só para `CLIENTS_SENSITIVE_READ`; toda geração fica na auditoria. |
| Pesquisa global | Cada categoria respeita a permissão do módulo e o sigilo (processos, prazos e compromissos restritos ocultos sem `CRIMINAL_READ`). |
| CRM (LGPD) | Minimização: oportunidades não guardam CPF/CNPJ (coletado na conversão, já cifrado no cadastro do cliente); exclusão definitiva disponível ao responsável/sócio (direito de eliminação). |
| Exportações | CSV com neutralização de injeção de fórmulas (`=`, `+`, `-`, `@`) e registro na auditoria. |

## Pendências (próximas etapas)

* Antivírus nos uploads (a verificação por assinatura binária, tamanho e nome seguro já está ativa para comprovantes).
* Registro de tentativas de login malsucedidas (via Identity Platform blocking functions) para o Painel Master.
* Rotação de chaves de criptografia (versão `v1:` já prevista no formato do dado cifrado).
* Política de retenção configurável por escritório e rotina de anonimização.
* Backup agendado: `gcloud firestore export gs://<bucket>/backups/$(date +%F)` via Cloud Scheduler, com retenção de 30/90 dias e teste de restauração trimestral.
* MFA (TOTP) para perfis administrativos.
