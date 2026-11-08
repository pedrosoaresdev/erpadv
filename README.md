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
* **Dashboard executivo**: clientes (ativos/inativos/novos), processos (ativos, suspensos, encerrados, arquivados,
  aguardando movimentação), prazos de hoje/3/7/15 dias e vencidos, audiências e agenda, contratos, honorários,
  despesas, faturamento, inadimplência e rentabilidade; gráficos por área, tribunal, situação, origem dos clientes,
  prazos cumpridos × não cumpridos e desempenho da equipe; filtro por período e por advogado, comparação com o
  período anterior, exportação PDF/Excel e atalhos de cadastro.
* **Navegação mobile**: barra inferior com atalhos, menu completo em gaveta, formulários em tela cheia e tabelas
  em cartões no celular; menu lateral final com os módulos das próximas fases sinalizados (F2–F6).
* **Pesquisa global**: clientes, CPF/CNPJ, processos, nº CNJ, advogados, documentos, contratos, prazos,
  audiências, tarefas e movimentações, agrupados por categoria e filtrados pelas permissões.
* **Central de notificações**: prioridade, categorias, pesquisa, marcar como lida e acesso ao registro.
* **CRM de clientes**: PF/PJ (nacionalidade, inscrições estadual/municipal, representante legal, pessoas de
  contato, origem), CPF/CNPJ criptografado, pesquisa por nome/CPF/CNPJ/telefone/e-mail, aviso de duplicidade,
  arquivamento e exclusão conforme vínculos, clientes sem movimentação recente, ficha cadastral PDF/Excel e
  impressão, histórico de alterações, exportação, seleção múltipla e ações em lote, atendimentos, processos vinculados.
* **Processos**: CNJ validado, polos ativo/passivo, instância, juízo, assuntos secundários, justiça gratuita,
  segredo de justiça, fase processual, OAB do responsável e histórico de responsáveis; movimentações com
  destaque das importantes (sentença, recurso, suspensão e trânsito atualizam situação/fase); página em 12 abas
  (resumo, partes, movimentações, prazos, audiências, documentos, tarefas, financeiro, contratos, histórico,
  observações, integrações judiciais); duplicar, arquivar, excluir arquivado sem vínculos, relatório completo
  PDF/Excel, impressão e exportação da lista.
* **Prazos**: contagem sugerida em dias úteis (CPC, arts. 219, 220 e 224 — feriados nacionais e
  móveis, recesso de 20/12 a 20/01) ou corridos com prorrogação, feriados locais e suspensões por
  tribunal, validação obrigatória pelo advogado responsável, indicadores (hoje, urgentes, futuros,
  vencidos, concluídos) e alertas automáticos 10, 5, 3 e 1 dia antes e no dia do vencimento; origem da
  comunicação (DJe, intimação eletrônica/pessoal, citação…), datas de disponibilização, publicação (sugerida
  pelo calendário forense) e ciência, comprovante de cumprimento, histórico com antes/depois, relatório por
  responsável (PDF/Excel) e filtros por advogado, processo e cliente.
* **Agenda**: audiências, reuniões, atendimentos, julgamentos, sustentações orais, despachos, perícias,
  diligências, compromissos e atividades internas em visões dia/semana/mês, agenda por advogado, recorrência
  (diária, semanal, quinzenal, mensal), convidados e confirmação de presença, link de videoconferência e local,
  status com resultado, exportação .ics (Google Calendar/Outlook), histórico, relatório, lembretes e notificações.
* **Audiências**: painel das próximas e realizadas (30/90 dias), por advogado e situação, com registro do
  resultado, indicadores e relatório.
* **Tarefas**: lista única das tarefas da equipe (cartões de todos os quadros), criação rápida, minhas/da equipe,
  abertas/atrasadas/concluídas, vínculo com cliente e processo.
* **Central de documentos**: pastas e subpastas, etiquetas, favoritos, renomear, mover, copiar, compartilhar com
  a equipe, lixeira com restauração e eliminação definitiva, versões, PDF/DOC/DOCX/XLS/XLSX/PPT/PPTX/JPG/PNG/TXT,
  envio múltiplo com arrastar e soltar, limite configurável (`DOCUMENTS_MAX_MB`), aviso de duplicidade e
  visualizador integrado (PDF com zoom, páginas, miniaturas, rotação, busca no texto, tela cheia, impressão e
  download; imagens e texto).
* **Editor Jurídico**: 14 modelos do sistema (procuração, contrato de honorários, petição inicial, contestação,
  réplica, recurso, contrarrazões, declaração de hipossuficiência, substabelecimento, notificação extrajudicial,
  requerimento, parecer, relatório processual e documento em branco) e modelos do escritório; campos automáticos
  de cliente, processo, escritório e advogado (pendentes destacados); cabeçalho, rodapé e numeração de páginas;
  exportação PDF/DOCX, impressão, envio à central de documentos; histórico de versões com restauração;
  rascunho/final; **papel timbrado** do escritório (PNG/JPG ou PDF, ficheiro com vários papéis, padrão para novos
  documentos, margens da área de texto) aplicado no editor, no PDF, no DOCX e na impressão.
* **Reuniões**: reuniões e atendimentos presenciais ou por videoconferência, com a plataforma escolhida pelo
  advogado (Google Meet, Zoom, Microsoft Teams ou outro link, validado pelo domínio oficial), atalho para criar a sala
  na conta do advogado, sala da reunião no sistema (cronômetro, participantes, vínculos e ata) com a chamada aberta
  numa janela ao lado — Google Meet e Zoom não permitem ser exibidos dentro de outro site.
* **Financeiro**: contas a receber e a pagar com categorias de receitas (honorários, êxito, consultorias,
  contratos, serviços) e despesas (aluguel, energia, internet, pessoal, sistemas, marketing, fornecedores…),
  parcelamentos e despesas recorrentes, baixa com valor efetivo e comprovante (Firebase Storage via
  backend), fluxo de caixa, projeção de 6 meses, DRE gerencial, inadimplência com aging e marcação
  automática no CRM, exportação CSV e finanças pessoais dos sócios separadas das do escritório; centros de custo,
  contas bancárias com saldo e extrato, recibo em PDF (no papel timbrado), cobranças com mensagem pronta, link do
  WhatsApp e histórico.
* **Honorários**: contratado, recebido, pendente e em atraso por cliente, processo, contrato e tipo (inclui
  sucumbência e partido mensal), parcelas e histórico de pagamentos, percentual de êxito; comissões com regras por
  integrante (geral, por cliente, por processo ou só processos sob sua responsabilidade), apuração por período,
  lançamento no contas a pagar sem duplicidade e "Minhas comissões" para cada integrante.
* **Central de relatórios**: 19 relatórios (clientes, processos por área/advogado/tribunal, ativos e encerrados,
  prazos vencidos e cumpridos, audiências, contratos ativos e vencidos, honorários recebidos e pendentes,
  despesas, fluxo de caixa, produtividade da equipe, desempenho comercial e histórico de alterações) em PDF e
  Excel, com cabeçalho do escritório, período e data de geração, filtrados pelas permissões.
* **Contratos e honorários**: fixos, parcelados, por etapas, de êxito e partido mensal; ativação gera as
  contas a receber; faturamento de etapas e do êxito; modelos com campos dinâmicos (valor por extenso,
  qualificação do cliente, dados do processo); minuta exportada em PDF e DOCX; contrato assinado anexado;
  histórico de alterações; alertas de término (30/7/1/0 dias); tipos prestação de serviços e administrativo;
  renovação com novas mensalidades e reajuste.
* **Documentos**: repositório no Firebase Storage com categorias, organização por cliente/processo,
  versionamento, pré-visualização, histórico de acesso, sigilo e arquivamento.
* **Equipe e cargos**: cargos personalizados do escritório (Advogado júnior, Estagiário, Secretário(a),
  Financeiro… com modelos prontos) e permissões granulares; cadastro com senha inicial ou link, CPF
  cifrado, limite de usuários do plano.
* **Quadros (Kanban jurídico)**: modelos (contencioso, entrada de cliente, consultivo, audiências),
  arrastar e soltar, limite WIP por coluna, automações ao entrar na coluna (concluir, notificar,
  etiquetar, atribuir, definir vencimento), checklist, vínculo com cliente/processo, comentários com
  @menções, tempo parado na coluna, filtros, visão em lista, "Meus cartões", conversão do cartão em
  prazo processual e indicadores (vazão semanal, ciclo médio, gargalos, carga por pessoa).
* **CRM comercial**: funil com as etapas da especificação (novo contato → primeiro atendimento → qualificação →
  reunião agendada → proposta enviada → aguardando decisão → contrato enviado → cliente convertido/atendimento
  encerrado), propostas de honorários em PDF que avançam o funil, arrastar e soltar,
  pontuação automática (quente/morno/frio), próxima ação com alerta de atraso, histórico de interações,
  motivo de perda obrigatório, conversão em cliente e métricas (pipeline ponderado, conversão por
  origem e por responsável, entradas × ganhos por mês).
* **Painel da plataforma (master)**: o desenvolvedor cria escritórios já com o advogado administrador
  (nome, CPF, Nº OAB, e-mail e senha), define plano e limite de usuários, suspende/reativa e redefine
  senhas. Acesso pelos e-mails em `PLATFORM_MASTER_EMAILS`.
* **Central Judicial (Fase 4)**: tribunais e sistemas com a forma oficial de acesso de cada um; sincronização com
  a API Pública do DataJud (CNJ) por processo ou diária; publicações do DJEN (Comunica PJe) por OAB com vínculo ao
  processo e criação de prazo; importação assistida por planilha CSV. Sem captura de tela nem senha judicial.
* **Comunicação (Fase 4)**: histórico por cliente/processo, e-mail (SMTP) com anexos da central, WhatsApp Business
  (Cloud API oficial, envio e recebimento), registro de atendimentos, lembretes de retorno e modelos de mensagem.
* **Integrações (Fase 4)**: Google Agenda por usuário (compromissos e sala do Meet), assinatura eletrônica ZapSign,
  convites da agenda por e-mail. Credenciais configuradas em *Integrações* (somente no backend, cifradas).
* **Auditoria**: trilha imutável encadeada por hash, com verificação de integridade.
* **Busca global**, **notificações em tempo real**, **tema claro/escuro**, **sidebar recolhível**.

Criminal e IA aparecem na sidebar com a etapa prevista.

No Docker local, os e-mails enviados pelo sistema caem no **Mailpit** (http://localhost:18025), sem sair da máquina.
Para os conectores reais, preencha as variáveis de `.env.example` (`DATAJUD_API_KEY`, `SMTP_*`, `GOOGLE_*`) ou
configure cada escritório em *Integrações*.

### Acesso master (desenvolvedor)

Defina `PLATFORM_MASTER_EMAILS=seu@email` no `.env`, recrie o backend (`docker compose up -d backend`)
e crie a conta em **Criar conta** com esse e-mail: ao entrar, você cai no painel **Plataforma → Escritórios**.

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
| Backend unitário + segurança | `cd backend && mvn test` | saneamento do editor, campos escapados, modelos, exportação PDF (numeração) e DOCX (cabeçalho/rodapé/tabelas), recorrência da agenda, formatos OOXML/OLE/TXT, permissões da lixeira/pastas/editor/relatórios; relatórios PDF/Excel (injeção de fórmulas), agrupamentos do dashboard, pesquisa sem acentos, CPF/CNPJ/CNJ, criptografia, RBAC, cadeia de auditoria, serviço de clientes, contagem de prazos (feriados, recesso, suspensões), política de alertas, DRE, validação de arquivos, CSV, valor por extenso, minuta, exportação PDF/DOCX, filtro JWT (401/403, bloqueio, revogação, isolamento, validação) |
| Backend ponta a ponta | `cd firebase && npm run test:backend-e2e` | Fase 4 (DataJud com movimentações sem duplicidade, DJEN → processo → prazo, importação CSV, e-mail sem SMTP, WhatsApp envio + webhook assinado/recusado + verificação, atendimento com retorno, modelos, ZapSign com via assinada como nova versão, remoção de segredo — APIs externas substituídas por servidor local de teste); Fase 3 (centro de custo e filtro, conta com saldo/extrato, baixa por conta, recibo, cobrança com mensagem e WhatsApp, honorários por cliente, comissão com lançamento único, renovação de contrato, etapas do CRM e propostas, os 19 relatórios); Fase 2 (prazo com origem/publicação/comprovante/histórico/relatório; série recorrente com convidados, confirmação, status, ICS, audiências e relatório; tarefas; pastas, ciclo recusado, XLSX, etiquetas, duplicidade, favoritos, mover/copiar, compartilhar, lixeira, restauração e eliminação auditada; editor: modelos, campos, saneamento, versões, finalização, PDF/DOCX, envio à central, restauração, modelo do escritório); Fase 1 (cliente PJ completo, duplicidade, ficha, exclusão com vínculos, processo com sentença/recurso, abas, relatório, duplicar/excluir, dashboard por advogado e exportação, pesquisa global); painel master → escritório com administrador → cargo personalizado → integrante com senha → quadros (WIP, automação, menções, indicadores) → CRM (funil, conversão) → limite de usuários → suspensão; e o fluxo: cadastro → escritório → clientes → processos → isolamento → dashboard → auditoria → calendário forense → prazos (validação, alertas idempotentes) → agenda (lembretes) → financeiro (parcelas, inadimplência no CRM, baixa, comprovante no Storage, DRE, CSV, escopo pessoal) → documentos (versões, acessos, arquivamento) → contratos (minuta, PDF/DOCX, ativação, êxito, etapas, assinado, cancelamento) → convite/bloqueio, com Auth e Firestore reais no emulador |
| Security Rules | `cd firebase && npm run test:rules` | Perfis, isolamento, auditoria imutável, notificações, Storage |
| Frontend | `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless` | Árvore de pastas, busca no PDF, saneamento do editor, validadores, pipes, guards, interceptor, formulário de cliente, utilitários da agenda, de prazos e do financeiro |
