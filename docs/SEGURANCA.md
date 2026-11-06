# Segurança, sigilo profissional e LGPD

## Controles implementados

| Controle | Implementação |
|---|---|
| Autenticação | Firebase Authentication (e-mail/senha, verificação de e-mail, recuperação de senha). Backend valida todo ID token com o Admin SDK. |
| Autorização | RBAC com 11 papéis e 33 permissões granulares; concessões/revogações por usuário; `@PreAuthorize` em todos os endpoints; front esconde o que não é permitido, mas o backend decide. |
| Isolamento multiempresa | Dados em `offices/{officeId}/…` + campo `officeId`; `officeId` vem do claim do token e é conferido com o perfil persistido (`PROFILE_MISMATCH`). Repositórios exigem `officeId` em toda operação. Teste E2E prova que o escritório B não lê dados do A. |
| Processos criminais/sigilosos | Flag `restricted`: invisíveis (404) para quem não tem `CRIMINAL_READ`; consulta auditada. |
| Security Rules | Firestore e Storage negam escrita do cliente; leitura só do próprio perfil e das próprias notificações. 15 testes automatizados no emulador. |
| Dados pessoais | CPF/CNPJ e RG cifrados com AES-256-GCM; busca por HMAC-SHA256; exibição mascarada para perfis sem `CLIENTS_SENSITIVE_READ`; visualização completa é auditada. |
| Auditoria imutável | Cadeia SHA-256 encadeada, gravação transacional com `create`, sem endpoints de alteração/exclusão; verificação de integridade em `/api/audit/verify`. |
| Sessões | Bloqueio desabilita o usuário no Firebase, revoga refresh tokens e invalida tokens já emitidos (`tokensRevokedAt`). |
| Rate limiting | Nginx (`limit_req`, 20 r/s por IP; 5 r/s no onboarding) + filtro na API (300 req/min por usuário). |
| Transporte | HTTPS (TLS 1.2/1.3) no Nginx, HSTS, redirecionamento 80→443. |
| XSS | Angular escapa interpolação por padrão; CSP sem `unsafe-inline` para scripts (build sem *critical CSS inline*); API responde `default-src 'none'`. |
| CSRF | Não aplicável: API stateless com Bearer token em cabeçalho (sem cookies de sessão). |
| Validação | Bean Validation em todos os DTOs + validação de CPF/CNPJ/CNJ no front e no back; erros padronizados (RFC 7807) sem stack trace. |
| Credenciais | Nenhuma credencial administrativa no front ou nas imagens; service account via Docker secret; chaves de desenvolvimento recusadas com `APP_PRODUCTION=true`. |
| Logs | JSON estruturado com `traceId`, `uid`, `officeId`; logger `AUDIT` dedicado. |

## Pendências (próximas etapas)

* Upload de documentos com verificação de assinatura binária (magic bytes) e antivírus (Etapa 11).
* Registro de tentativas de login malsucedidas (via Identity Platform blocking functions) para o Painel Master.
* Rotação de chaves de criptografia (versão `v1:` já prevista no formato do dado cifrado).
* Política de retenção configurável por escritório e rotina de anonimização.
* Backup agendado: `gcloud firestore export gs://<bucket>/backups/$(date +%F)` via Cloud Scheduler, com retenção de 30/90 dias e teste de restauração trimestral.
* MFA (TOTP) para perfis administrativos.
