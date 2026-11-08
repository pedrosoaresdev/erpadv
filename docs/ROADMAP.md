# Planejamento por etapas

Legenda: ✅ concluída · 🟡 parcial · ⏳ pendente

| Etapa | Escopo | Status |
|---|---|---|
| 1 | Arquitetura, modelagem Firestore, fluxo de autenticação, estrutura de pastas | ✅ |
| 2 | Firebase: `firebase.json`, Security Rules (Firestore/Storage), índices, Emulator Suite, testes de rules | ✅ |
| 3 | Backend Java: Firebase Admin, segurança, RBAC, auditoria encadeada, criptografia, paginação, tratamento de erros, logs | ✅ |
| 4 | Frontend Angular: layout premium, tema claro/escuro, sidebar recolhível, configuração em runtime | ✅ |
| 5 | Autenticação e permissões: login, cadastro do escritório, recuperação de senha, verificação de e-mail, convites, bloqueio, revogação de sessões | ✅ |
| 6 | Dashboard executivo (clientes, processos, prazos, audiências, financeiro e contratos) | ✅ |
| 7 | CRM de clientes (cadastro PF/PJ, pesquisa, inativação, atendimentos, página do cliente) | ✅ |
| 8 | Gestão processual (cadastro, arquivamento, movimentações, decisões, despachos, linha do tempo, filtros) | ✅ |
| 9 | Prazos (contagem CPC com feriados, recesso e suspensões; validação pelo advogado; alertas 10/5/3/1/0 dias via notificação e FCM) e agenda (dia/semana/mês, lembretes) — ampliados na Fase 2 | ✅ |
| 10 | Financeiro (receber/pagar, parcelamentos, recorrências, baixa com comprovante, fluxo de caixa, projeções, DRE gerencial, inadimplência integrada ao CRM, CSV, finanças pessoais dos sócios) | ✅ |
| 11 | Contratos/honorários (fixo, parcelado, etapas, êxito, mensal → financeiro), modelos com campos dinâmicos, minuta PDF/DOCX, contrato assinado, histórico; gestão documental (Storage, versões, sigilo, histórico de acesso, pré-visualização) | ✅ |
| 12 | Departamento criminal (custodiados, inquéritos, cautelares) | ⏳ |
| 13 | IA jurídica (6 agentes via backend, regras de não-alucinação, revisão humana) | ⏳ |
| 14 | Portal do cliente | ⏳ |
| 15 | Painel Master / TI | 🟡 (painel da plataforma, cargos personalizados, auditoria e gestão de usuários prontos; relatórios pendentes) |
| + | Quadros Kanban jurídicos (WIP, automações, indicadores, cartão → prazo) e CRM comercial (funil, pontuação, conversão em cliente, métricas) | ✅ |
| 16 | Docker, Nginx (HTTPS), Compose com Emulator Suite | ✅ |
| 17 | Testes e segurança (ampliação contínua a cada etapa) | 🟡 |

## Plano de evolução — especificação completa (6 fases)

A especificação "ERPAdv — sistema de gestão jurídica completo" é implantada em fases, sem quebrar o que já existe.
Banco de dados: a especificação original define o Firebase/Firestore como persistência principal e proíbe SGBDs
relacionais; essa decisão foi mantida (multi-tenant por `officeId`, backup por export do Firestore) para preservar
os dados já cadastrados.

| Fase | Escopo | Status |
|---|---|---|
| 1 — Fundação | Menu final (seção 23) com fases indicadas, navegação mobile (barra inferior, formulários em tela cheia, tabelas em cartões), dashboard executivo ampliado (prazos 3/7/15 dias, suspensos, aguardando movimentação, agenda, tribunal, origem, desempenho da equipe, comparação com período anterior, filtro por advogado, exportação PDF/Excel, atalhos), clientes PF/PJ completos (nacionalidade, inscrições, representante, contatos, origem, duplicidade, exclusão com política de guarda, sem movimentação recente, ficha PDF/Excel, impressão, histórico, exportação, seleção múltipla/ações em lote/colunas), processos avançados (instância, juízo, polos, assuntos secundários, justiça gratuita, fase, OAB, histórico de responsáveis, movimentações importantes, sentença/recurso/suspensão/trânsito, 12 abas, duplicar, excluir arquivado sem vínculos, relatório PDF/Excel, exportação), pesquisa global em todos os módulos, central de notificações | ✅ |
| 2 — Operação jurídica | Prazos (origem da comunicação, datas de disponibilização/publicação/ciência com sugestão da publicação no DJe, comprovante de cumprimento, histórico antes/depois, relatório por responsável, filtros por advogado/processo/cliente); agenda (novos tipos, agenda por advogado, recorrência, convidados com confirmação de presença, videoconferência, local, status e resultado, exportação .ics para Google Calendar/Outlook, relatório); módulos **Audiências** e **Tarefas**; central documental (pastas e subpastas, etiquetas, favoritos, renomear/mover/copiar, compartilhamento interno, lixeira com restauração e eliminação definitiva, versões, PDF/DOC/DOCX/XLS/XLSX/PPT/PPTX/JPG/PNG/TXT, envio múltiplo com arrastar e soltar, limite configurável, detecção de duplicidade, visualizador PDF com zoom, páginas, miniaturas, rotação, busca no texto, tela cheia e impressão); **Editor Jurídico** (14 modelos do sistema, modelos do escritório, campos automáticos de cliente/processo/escritório/advogado, cabeçalho/rodapé, numeração de páginas, PDF/DOCX, impressão, histórico de versões, rascunho/final, envio à central) | ✅ |
| 3 — Gestão empresarial | Honorários (sucumbência, comissões), centros de custo, contas bancárias, cobranças, central de relatórios, etapas do CRM da especificação | ⏳ (financeiro, contratos e CRM já existem) |
| 4 — Integrações | Central Judicial (DataJud/PJe e demais, somente por interfaces oficiais), DJEN, WhatsApp Business, e-mail, Google Calendar, assinatura eletrônica | ⏳ |
| 5 — Inteligência e segurança | IA jurídica com fontes verificáveis, automações, OCR, auditoria com antes/depois, 2FA, controle de dispositivos, LGPD (titulares, consentimentos, incidentes) | ⏳ |
| 6 — Comercialização | Planos e assinatura, licenças, configurações do escritório (logotipo, cabeçalho de documentos), unidades/filiais, monitoramento | 🟡 (multi-tenant, painel master, planos e limite de usuários já existem) |

Pendências conhecidas da Fase 1: filtro do dashboard por **unidade do escritório** depende do cadastro de unidades
(Fase 6); foto do cliente ainda não é armazenada.

Pendências conhecidas da Fase 2: OCR de documentos digitalizados e assinatura eletrônica ficam nas Fases 5 e 4,
como prevê a especificação; sincronização bidirecional com o Google Calendar pela API oficial é da Fase 4 (hoje a
exportação é por arquivo .ics); convites por e-mail aos convidados externos dependem do e-mail transacional;
DOC/DOCX/XLS/PPT são baixados para abrir no aplicativo (PDF, imagens e TXT têm visualização integrada); não há
comparação lado a lado entre versões do editor.

## O que falta (registrado ao fim desta entrega)

* Quadros: anexos diretos no cartão (hoje o vínculo é com cliente/processo, cujos documentos ficam no repositório), lembretes automáticos de vencimento dos cartões e quadros-modelo criados pelo próprio escritório.
* CRM: captura automática de leads (formulário do site/WhatsApp) e envio de e-mail ao lead dependem de integração externa.
* Painel da plataforma: cobrança/faturamento dos planos e métricas de uso por escritório.
* Módulos das etapas 12–14 (rotas aparecem desabilitadas na sidebar com a etapa indicada).
* Assinatura eletrônica com certificado ICP-Brasil e antivírus nos uploads.
* Financeiro: DRE em regime de competência, conciliação bancária (OFX) e emissão de boletos/Pix dependem de integração com instituição financeira.
* Exportação PDF/Excel dos relatórios financeiros (CSV já disponível) entra com a central de relatórios.
* Calendários forenses estaduais/municipais pré-carregados (hoje cadastrados pelo escritório em *Prazos → Calendário forense*).
* Regras especiais de contagem não presumidas: prazo em dobro (CPC, arts. 180, 183, 186 e 229), intimação eletrônica tácita e publicação no DJe — informadas pelo usuário.
* Envio push (FCM) exige projeto Firebase real e chave VAPID; no Emulator Suite as notificações chegam pelo sino em tempo real.
* Envio de e-mail transacional para convites (hoje o link é exibido ao administrador).
* Integração com consulta processual oficial (ex.: DataJud/CNJ) — interface preparada em
  `CaseMovement.source`; depende de credencial e termos de uso.
* Backup agendado do Firestore (`gcloud firestore export`) — documentado em `docs/SEGURANCA.md`.
