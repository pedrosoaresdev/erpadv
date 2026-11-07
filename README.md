# ERPAdv — Gestão para escritórios de advocacia

Software jurídico empresarial multiempresa (SaaS): **Angular 20 + Angular Material**, **Java 21 /
Spring Boot 3.5 / Spring Security**, **Firebase** (Authentication, Cloud Firestore, Storage, Cloud
Messaging, Security Rules, Admin SDK), **Docker + Nginx**.

> Desenvolvido em etapas. Veja o andamento em [`docs/ROADMAP.md`](docs/ROADMAP.md).

## Executar com um comando

Pré-requisito: Docker com Compose v2.

```bash
docker compose up --build
```

| Serviço | Endereço |
|---|---|
| Aplicação (HTTPS, certificado autoassinado de desenvolvimento) | https://localhost:8443 |
| Firebase Emulator UI (usuários, Firestore) | http://localhost:4000 |

O ambiente sobe com o **Firebase Emulator Suite** (projeto `demo-erpadv`) — não precisa de conta
Firebase. Crie sua conta em **Criar escritório**: você vira sócio administrador e pode convidar a
equipe. Os dados do emulador persistem no volume `emulator-data`.

Portas, chaves e demais variáveis: [`.env.example`](.env.example) (copie para `.env` para alterar).

## Estrutura

```
backend/            API Spring Boot (config, security, controllers, services, repositories,
                    models, dto, exceptions, firebase, audit, validation)
frontend/           Angular (core, shared, layouts, modules/*)
firebase/           Security Rules, índices, configuração dos emuladores e testes das regras
docker/             Nginx (TLS, CSP, proxy, config em runtime) e imagem do Emulator Suite
docs/               Arquitetura, modelagem do Firestore, segurança e roadmap
docker-compose.yml       desenvolvimento (emuladores)
docker-compose.prod.yml  produção (Firebase real)
```

Documentação: [arquitetura e fluxo de autenticação](docs/ARQUITETURA.md) ·
[modelagem do Firestore](docs/FIRESTORE.md) · [segurança e LGPD](docs/SEGURANCA.md).

## Módulos disponíveis nesta entrega

* **Autenticação**: login, criação do escritório, verificação de e-mail, recuperação de senha,
  convites, bloqueio, revogação de sessões.
* **Dashboard executivo**: clientes, processos, crescimento, áreas, situações, carteira por advogado,
  filtro de período.
* **CRM de clientes**: PF/PJ, CPF/CNPJ criptografado, pesquisa por nome/CPF/CNPJ/telefone/e-mail,
  inativação, inadimplência, atendimentos, processos vinculados.
* **Processos**: CNJ validado, partes, juízo, responsável, valor, situação, movimentações,
  decisões, despachos, linha do tempo, arquivamento, filtros, restrição criminal/sigilosa.
* **Prazos**: contagem sugerida em dias úteis (CPC, arts. 219, 220 e 224 — feriados nacionais e
  móveis, recesso de 20/12 a 20/01) ou corridos com prorrogação, feriados locais e suspensões por
  tribunal, validação obrigatória pelo advogado responsável, indicadores (hoje, urgentes, futuros,
  vencidos, concluídos) e alertas automáticos 10, 5, 3 e 1 dia antes e no dia do vencimento.
* **Agenda**: audiências, reuniões, consultas, julgamentos, sustentações orais, compromissos e
  atividades internas em visões dia/semana/mês, com prazos sobrepostos, responsáveis, vínculo com
  cliente/processo, lembretes e notificações (tempo real + push FCM).
* **Financeiro**: contas a receber e a pagar com categorias de receitas (honorários, êxito, consultorias,
  contratos, serviços) e despesas (aluguel, energia, internet, pessoal, sistemas, marketing, fornecedores…),
  parcelamentos e despesas recorrentes, baixa com valor efetivo e comprovante (Firebase Storage via
  backend), fluxo de caixa, projeção de 6 meses, DRE gerencial, inadimplência com aging e marcação
  automática no CRM, exportação CSV e finanças pessoais dos sócios separadas das do escritório.
* **Contratos e honorários**: fixos, parcelados, por etapas, de êxito e partido mensal; ativação gera as
  contas a receber; faturamento de etapas e do êxito; modelos com campos dinâmicos (valor por extenso,
  qualificação do cliente, dados do processo); minuta exportada em PDF e DOCX; contrato assinado anexado;
  histórico de alterações; alertas de término (30/7/1/0 dias).
* **Documentos**: repositório no Firebase Storage com categorias, organização por cliente/processo,
  versionamento, pré-visualização, histórico de acesso, sigilo e arquivamento.
* **Equipe**: papéis e permissões granulares por usuário.
* **Auditoria**: trilha imutável encadeada por hash, com verificação de integridade.
* **Busca global**, **notificações em tempo real**, **tema claro/escuro**, **sidebar recolhível**.

Criminal, IA, portal do cliente, relatórios e
painel master aparecem na sidebar com a etapa prevista.

## Produção com Firebase real

1. Crie um projeto no [Firebase Console](https://console.firebase.google.com) e habilite
   **Authentication → E-mail/senha**, **Cloud Firestore** (modo produção) e **Storage**.
2. Publique regras e índices: `cd firebase && npx firebase deploy --only firestore,storage --project <id>`.
3. Em *Configurações do projeto → Geral*, registre um app Web e copie a configuração pública para o `.env`
   (`FIREBASE_WEB_API_KEY`, `FIREBASE_AUTH_DOMAIN`, `FIREBASE_APP_ID`, …). Para push, gere em
   *Cloud Messaging → Certificados push da Web* a chave e preencha `FIREBASE_VAPID_KEY`.
4. Em *Contas de serviço*, gere uma chave privada e salve em `secrets/firebase-service-account.json`
   (pasta ignorada pelo git; montada como Docker secret, nunca copiada para a imagem).
5. Gere chaves próprias: `openssl rand -base64 32` (DATA_ENCRYPTION_KEY) e `openssl rand -base64 48`
   (SEARCH_HMAC_KEY). **Guarde-as em cofre: sem elas os CPFs cifrados não podem ser lidos.**
6. Coloque o certificado TLS em `docker/certs/tls.crt` e `tls.key`, ajuste `REQUIRE_EMAIL_VERIFIED=true`
   e `CORS_ALLOWED_ORIGINS`, e rode `docker compose -f docker-compose.prod.yml up -d --build`.

## Desenvolvimento sem Docker

```bash
cd firebase && npm install && npm run emulators          # Auth + Firestore + Storage
cd backend  && mvn spring-boot:run                       # com as variáveis do .env.example + FIRESTORE_EMULATOR_HOST=localhost:8080,
                                                         # FIREBASE_AUTH_EMULATOR_HOST=localhost:9099, FIREBASE_USE_EMULATOR=true
cd frontend && npm install && npm start                  # http://localhost:4200 (proxy /api → :8085)
```

## Testes

| Suíte | Comando | O que cobre |
|---|---|---|
| Backend unitário + segurança | `cd backend && mvn test` | CPF/CNPJ/CNJ, criptografia, RBAC, cadeia de auditoria, serviço de clientes, contagem de prazos (feriados, recesso, suspensões), política de alertas, DRE, validação de arquivos, CSV, valor por extenso, minuta, exportação PDF/DOCX, filtro JWT (401/403, bloqueio, revogação, isolamento, validação) |
| Backend ponta a ponta | `cd firebase && npm run test:backend-e2e` | Cadastro → escritório → clientes → processos → isolamento → dashboard → auditoria → calendário forense → prazos (validação, alertas idempotentes) → agenda (lembretes) → financeiro (parcelas, inadimplência no CRM, baixa, comprovante no Storage, DRE, CSV, escopo pessoal) → documentos (versões, acessos, arquivamento) → contratos (minuta, PDF/DOCX, ativação, êxito, etapas, assinado, cancelamento) → convite/bloqueio, com Auth e Firestore reais no emulador |
| Security Rules | `cd firebase && npm run test:rules` | Perfis, isolamento, auditoria imutável, notificações, Storage |
| Frontend | `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless` | Validadores, pipes, guards, interceptor, formulário de cliente, utilitários da agenda, de prazos e do financeiro |
