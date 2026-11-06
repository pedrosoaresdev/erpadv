# ERPAdv — Arquitetura

Sistema de gestão para escritórios de advocacia, multiempresa (SaaS), construído com
**Angular + Spring Boot + Firebase**, empacotado com **Docker / Nginx**.

---

## 1. Visão geral

```
                    ┌──────────────────────────── Navegador ────────────────────────────┐
                    │  Angular (SPA)                                                     │
                    │   • Firebase Auth Web SDK  → login, cadastro, reset, verificação   │
                    │   • HttpClient + Bearer <ID token>  → toda leitura/escrita de dados │
                    │   • FCM Web (opcional) → recebe notificações push                   │
                    └───────────────┬───────────────────────────────┬────────────────────┘
                                    │ HTTPS (443)                   │ (somente identidade)
                                    ▼                               ▼
                    ┌──────────────────────────────┐      ┌──────────────────────────┐
                    │ Nginx                         │      │ Firebase Authentication  │
                    │  • serve o build Angular      │      └──────────────────────────┘
                    │  • proxy reverso /api → API   │
                    │  • TLS, CSP, HSTS, rate limit │
                    └───────────────┬──────────────┘
                                    │ rede interna Docker
                                    ▼
                    ┌───────────────────────────────────────────────────────────────┐
                    │ Spring Boot (Java 21)                                          │
                    │  FirebaseAuthenticationFilter → verifica ID token (Admin SDK)  │
                    │  RBAC (Role → Permission) + @PreAuthorize por operação          │
                    │  Services (regras de negócio, validação, criptografia LGPD)    │
                    │  Repositories Firestore (escopo obrigatório por officeId)      │
                    │  AuditService (trilha encadeada por hash, somente inserção)    │
                    └──────┬──────────────────┬───────────────────┬─────────────────┘
                           ▼                  ▼                   ▼
                  Cloud Firestore     Firebase Storage     Firebase Cloud Messaging
```

### Princípios

| Princípio | Como é aplicado |
|---|---|
| **Backend é a única porta de dados** | O Angular não lê nem grava Firestore/Storage diretamente. Usa o Firebase apenas para identidade. Toda regra de negócio, validação e auditoria acontece no Java. |
| **Defesa em profundidade** | Security Rules negam toda escrita do cliente e só liberam leituras mínimas do próprio usuário. Mesmo que o SDK web seja usado indevidamente, os dados ficam protegidos. |
| **Isolamento multiempresa** | Todas as entidades vivem em `offices/{officeId}/...` **e** carregam o campo `officeId`. O `officeId` vem do *custom claim* do token, nunca do corpo da requisição. |
| **Menor privilégio** | RBAC granular por módulo/operação/tipo de informação (ex.: `CLIENTS_SENSITIVE_READ` para ver CPF completo). |
| **Sigilo profissional / LGPD** | CPF/CNPJ e RG criptografados (AES-256-GCM) com busca por HMAC; documentos servidos apenas via backend autenticado; trilha de auditoria de quem leu, alterou, enviou ou baixou. |
| **Nenhuma credencial no frontend** | O front só recebe a configuração *pública* do Firebase Web (apiKey web é identificador público). Service account e chaves de IA ficam no backend, via variáveis de ambiente/volume somente leitura. |

---

## 2. Backend (Spring Boot)

```
backend/src/main/java/br/com/erpadv
├── config        FirebaseConfig, SecurityConfig, CorsProperties, JacksonConfig, AppProperties
├── security      FirebaseAuthenticationFilter, AuthenticatedUser, Role, Permission,
│                 RolePermissions, CurrentUser, RateLimitFilter, handlers JSON 401/403
├── controllers   REST: /api/auth, /api/onboarding, /api/clients, /api/cases,
│                 /api/dashboard, /api/employees, /api/audit
├── services      regras de negócio por módulo
├── repositories  FirestoreRepository<T> (base) + repositórios por coleção
├── models        entidades persistidas
├── dto           records de entrada/saída com Bean Validation
├── exceptions    ApiException + GlobalExceptionHandler (RFC 7807 ProblemDetail)
├── firebase      FirestoreMapper, CryptoService, SearchTokens, Futures
└── audit         AuditService, AuditLog, AuditAction
```

* **Spring Data**: usamos `spring-data-commons` (`Pageable`, `Sort`, `Slice`) como contrato dos
  repositórios. A implementação é feita sobre o Firebase Admin SDK, porque não existe módulo
  Spring Data oficial e síncrono para o Firestore — e o Admin SDK é obrigatório para validar
  tokens e definir *custom claims*.
* **Paginação**: sempre por cursor (`startAfter` + `limit`), nunca por *offset* (offset no
  Firestore cobra leituras dos documentos pulados). Tamanho máximo de página: 100.
* **Valores monetários**: armazenados em **centavos (long)**. Nunca `double`.
* **Datas**: `Instant` → epoch millis (ordenável); `LocalDate` → `"yyyy-MM-dd"` (ordenável).
* **Pesquisa**: o Firestore não tem busca textual. Cada documento pesquisável guarda
  `searchTokens` (prefixos normalizados do nome, dígitos do telefone, HMAC do CPF/CNPJ)
  consultados com `array-contains`.
* **Erros**: `application/problem+json` padronizado, com `traceId`.
* **Logs**: JSON estruturado (logstash encoder) em produção; MDC com `traceId`, `uid`, `officeId`.
* **Health checks**: `/actuator/health` (liveness/readiness) usados pelo Docker.

## 3. Frontend (Angular)

```
frontend/src/app
├── core          auth (serviço, guards, interceptor), api, config runtime, tema, notificações
├── shared        componentes reutilizáveis (page-header, stat-card, chart, empty-state,
│                 confirm-dialog), pipes (moeda em centavos, CPF), validators (CPF/CNPJ)
├── layouts       main-layout (sidebar recolhível + topbar), auth-layout
└── modules       dashboard, clients, cases, employees, admin, auth ...
                  (deadlines, calendar, finance, contracts, documents, criminal, ai,
                   client-portal, reports entram nas etapas seguintes)
```

* Componentes *standalone*, *signals*, Reactive Forms, rotas *lazy* por módulo.
* Angular Material com tema próprio (azul-marinho, grafite, cinza, dourado discreto),
  modo claro/escuro.
* Configuração em **tempo de execução** (`/assets/config.json`, gerado pelo container Nginx a
  partir de variáveis de ambiente) — a mesma imagem serve dev, homologação e produção.

## 4. Fluxo de autenticação

```
1. Usuário faz login no Angular  ──►  Firebase Auth (e-mail/senha)  ──►  ID token (JWT, 1h)
2. Angular chama a API com  Authorization: Bearer <ID token>
3. FirebaseAuthenticationFilter:
     a. FirebaseAuth.verifyIdToken(token)   → assinatura, expiração, audiência, emissor
     b. lê claims  officeId / role / clientId
     c. carrega o perfil users/{uid} (cache 60s): status ACTIVE? sessões revogadas depois do iat?
     d. calcula permissões = RolePermissions(role) + concessões − revogações do perfil
     e. popula o SecurityContext com AuthenticatedUser + authorities "PERM_*"
4. Controllers usam @PreAuthorize("hasAuthority('PERM_CLIENTS_WRITE')")
5. Services sempre recebem officeId do AuthenticatedUser — nunca do request.
```

**Primeiro acesso (criação do escritório)**: o usuário cria a conta no Firebase Auth, verifica
o e-mail e chama `POST /api/onboarding/office`. O backend cria `offices/{id}`, o perfil
`users/{uid}` com papel `SOCIO_ADMIN` e grava os *custom claims*. O front força
`getIdToken(true)` para receber os novos claims.

**Convite de funcionário**: o sócio chama `POST /api/employees`; o backend cria o usuário no
Firebase Auth, define claims, grava o perfil e gera um link de definição de senha
(`generatePasswordResetLink`) que é exibido ao administrador (envio por e-mail é uma
integração posterior).

**Bloqueio / revogação de sessão**: `revokeRefreshTokens(uid)` + `tokensRevokedAt` no perfil +
invalidação do cache → tokens já emitidos passam a ser rejeitados no próximo request.
Isso evita `checkRevoked=true` (que faria uma chamada remota por requisição).

**Portal do cliente**: usuários com papel `CLIENTE` recebem o claim `clientId`; só acessam
rotas `/api/portal/**`, que filtram por `clientId` e por documentos marcados como liberados.

## 5. RBAC

Papéis: `MASTER` (plataforma), `SOCIO_ADMIN`, `ADVOGADO`, `ADVOGADO_ASSOCIADO`, `ESTAGIARIO`,
`ASSISTENTE_JURIDICO`, `SECRETARIA`, `FINANCEIRO`, `ADMINISTRATIVO`, `TI_SUPORTE`, `CLIENTE`.

Permissões granulares (módulo + operação + tipo de informação), por exemplo:
`CLIENTS_READ`, `CLIENTS_WRITE`, `CLIENTS_SENSITIVE_READ`, `CLIENTS_INACTIVATE`,
`CASES_READ`, `CASES_WRITE`, `CASES_ARCHIVE`, `CRIMINAL_READ`, `DOCUMENTS_CONFIDENTIAL`,
`FINANCE_READ`, `EMPLOYEES_MANAGE`, `AUDIT_READ`, `ADMIN_PANEL` …
A matriz completa está em `backend/.../security/RolePermissions.java` e é exposta em
`GET /api/auth/me` para o front esconder o que o usuário não pode usar (o backend continua
sendo a autoridade).

## 6. Auditoria imutável

* Coleção `offices/{officeId}/audit_logs`, gravada **somente** pelo backend.
* Cada registro contém `seq`, `prevHash` e `hash = SHA-256(prevHash + conteúdo canônico)`,
  gravados em transação com o documento `offices/{officeId}/audit_meta/chain`.
* O backend não expõe nenhuma operação de alteração/remoção. As Security Rules negam escrita.
  Qualquer remoção ou edição fora do sistema quebra a cadeia e é detectada por
  `GET /api/audit/verify`.

## 7. Segurança (resumo)

HTTPS no Nginx (HSTS, redirecionamento 80→443), CSP restritiva, `X-Frame-Options`,
`Referrer-Policy`; verificação de token em toda rota `/api/**`; CSRF desabilitado por
ser API *stateless* com Bearer token (não há cookie de sessão a ser forjado);
rate limiting no Nginx e na API (por usuário/IP); Bean Validation em todos os DTOs;
criptografia de dados sensíveis; Security Rules restritivas; auditoria de acesso.
Detalhes e pendências em `docs/SEGURANCA.md`.
