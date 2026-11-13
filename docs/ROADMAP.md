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
| 3 — Gestão empresarial | Financeiro (novas categorias: sucumbência, recebimentos avulsos, tributos, materiais, custas, despesas processuais, deslocamentos, comissões; centros de custo; contas bancárias com saldo e extrato; baixa por conta; recibo em PDF no papel timbrado; cobranças com mensagem pronta, link oficial do WhatsApp e histórico); módulo **Honorários** (por cliente, processo, contrato e tipo — contratado, recebido, pendente, em atraso —, parcelas e histórico de pagamentos, percentual de êxito, comissões com regras por integrante, apuração, lançamento no contas a pagar sem duplicidade e "Minhas comissões"); contratos (prestação de serviços e administrativos, renovação com novas mensalidades e reajuste, histórico de renovações); CRM com as 9 etapas da especificação e propostas de honorários (rascunho, enviada, aceita/recusada, PDF); **Central de relatórios** com 19 relatórios em PDF/Excel | ✅ |
| 4 — Integrações | **Central Judicial**: catálogo de tribunais e sistemas com a forma oficial de acesso de cada um (DataJud/CNJ por API pública com chave; PJe, eproc, Projudi e e-SAJ sem API pública aberta → importação assistida e consulta no portal oficial, sem captura de tela nem senha judicial guardada); sincronização DataJud por processo e diária (movimentações sem duplicidade, campos vazios preenchidos, aviso ao responsável), histórico de execuções; **Publicações DJEN** (API Comunica PJe por OAB de cada advogado, 2×/dia, vínculo automático ao processo pelo nº CNJ, leitura, arquivamento e criação do prazo a partir da publicação); **importação assistida** por planilha CSV com modelo, validação de CNJ e relatório linha a linha; **central de comunicação** (histórico por cliente/processo, e-mail SMTP com anexos da central, WhatsApp Business Cloud API com modelos aprovados e recebimento por webhook assinado, registro de atendimentos, lembretes de retorno, modelos de mensagem com campos automáticos); convites da agenda por e-mail (.ics); **Google Agenda** por usuário (OAuth, envio dos compromissos e criação da sala do Meet); **assinatura eletrônica** ZapSign (envio de PDF da central, acompanhamento, via assinada guardada como nova versão) | ✅ |
| 5 — Inteligência e segurança | **Auditoria avançada** (valores anteriores e posteriores, módulo, origem, justificativa exigível em exclusões, filtros, exportação, cadeia de hash compatível com os registros antigos); **segurança** (2FA por aplicativo autenticador com códigos de recuperação e bloqueio, 2FA obrigatório por política, sessões controladas pelo servidor com duração máxima, inatividade, encerramento remoto e alerta de dispositivo novo, eventos de segurança, acessos negados registrados); **backup** diário cifrado no Storage com retenção, download, comparação e recuperação não destrutiva; **LGPD** (titulares, consentimentos, registro de tratamento, incidentes com prazo da ANPD, compartilhamentos, retenção documental, política de privacidade pública, exportação e anonimização); **OCR** local (Tesseract) e texto dos documentos na busca; **Recibos** para clientes. IA jurídica implementada e testada, mas fora da interface por decisão do escritório (uso futuro) | ✅ |
| 6 — Comercialização | **Planos e licenças** (Essencial, Profissional e Enterprise editáveis pelo master: preço mensal/anual, usuários, armazenamento e módulos; módulos fora do plano bloqueados também no servidor; escritórios antigos mantêm tudo até ganharem assinatura); **avaliação gratuita** de 14 dias no cadastro; **cobrança manual pelo painel master** (faturas FAT-AAAA-NNNNN emitidas na data da assinatura, PDF, baixa conferida pelo master, atraso com aviso, suspensão automática após a carência e reativação ao confirmar o pagamento — sem gateway); **Plano e Assinatura** do escritório (uso, faturas, forma de pagamento, pedido de mudança); **Configurações** (dados, endereço, OAB, logotipo no menu, módulos em uso, uso do sistema e atividade recente); **unidades/filiais** com integrantes, processos vinculados e filtro no dashboard e na lista de processos; **primeiros passos** no dashboard; **Suporte** (chamados com conversa entre escritório e plataforma); **monitoramento** da plataforma (receita recorrente, faturas, uso, chamados, alertas e saúde); menu **Sistemas** com o PJe e os sistemas dos tribunais | ✅ |

Pendências conhecidas da Fase 1: foto do cliente ainda não é armazenada. (O filtro do dashboard por unidade entrou na Fase 6.)

Pendências conhecidas da Fase 6: a cobrança é manual (sem gateway de pagamento, boleto ou Pix automático; o master confere
e dá baixa); nota fiscal de serviço não é emitida pelo sistema; os preços dos planos começam vazios e precisam ser definidos
pelo master antes de qualquer fatura; o PJe abre numa janela própria ao lado do ERPAdv, porque os tribunais proíbem exibi-lo
dentro de outros sites (cabeçalhos de segurança e cookies de sessão), e o login continua sendo o do tribunal (certificado
digital/PJe Office).

Complementos da Fase 2: papel timbrado no Editor Jurídico; módulo **Reuniões** (Google Meet, Zoom, Teams ou
presencial, sala com ata); Início ajustado ao celular; gráfico de receitas por categoria estabilizado.

Decisões do escritório após a Fase 5: o trabalho passa a ser feito manualmente pelo advogado. Saíram da interface a IA
Jurídica e a Pesquisa Jurídica (código mantido, conector desligado), a Automação de Rotinas (não implementada), a Central
Judicial (DataJud/DJEN — consultas automáticas desligadas; religáveis com `INTEGRATIONS_JUDICIAL_SYNC=true`), a tela de
Integrações, a assinatura eletrônica pela ZapSign e a central de comunicação (WhatsApp/e-mails). O envio de convites e de
cobranças por e-mail continua nas próprias telas. No lugar da comunicação entrou o módulo **Recibos**.

Pendências conhecidas da Fase 5: OCR depende do Tesseract instalado na imagem do backend (fora do Docker o status fica
"indisponível"); a recuperação de backup não recria perfis de usuários (dependem do Firebase Authentication) nem a auditoria
(imutável); arquivos binários da central documental não entram no backup lógico (ficam no Storage com versões).

Pendências conhecidas da Fase 4: consulta autenticada ao PJe/MNI e aos demais sistemas exige certificado digital
ICP-Brasil e convênio com cada tribunal — fica como fluxo assistido (portal oficial + importação de planilha e
documentos), sem automação de login; o DataJud tem atraso de atualização definido pelo CNJ e não traz o teor das
decisões; Zoom (criação de sala por API) e Microsoft Teams continuam por link colado e validado; a sincronização com
o Google é de mão única (sistema → Google); assinatura com certificado ICP-Brasil (padrão PAdES) depende de outro
provedor; envio de SMS não incluído.

Pendências conhecidas da Fase 3: conciliação bancária automática (OFX/Open Finance) e emissão de boletos/Pix
dependem de integração com instituição financeira; DRE por regime de competência. (Envio da cobrança por e-mail e
assinatura eletrônica entraram na Fase 4.)

Pendências conhecidas da Fase 2: criação automática das salas pelo sistema (API do Google Calendar/Meet e do Zoom,
com OAuth do escritório) e a videochamada embutida via Zoom Meeting SDK dependem de credenciais das plataformas
(Fase 4 — Integrações); OCR de documentos digitalizados e assinatura eletrônica ficam nas Fases 5 e 4,
como prevê a especificação; sincronização bidirecional com o Google Calendar pela API oficial é da Fase 4 (hoje a
exportação é por arquivo .ics); convites por e-mail aos convidados externos dependem do e-mail transacional;
DOC/DOCX/XLS/PPT são baixados para abrir no aplicativo (PDF, imagens e TXT têm visualização integrada); não há
comparação lado a lado entre versões do editor.

## O que falta (registrado ao fim desta entrega)

* Quadros: anexos diretos no cartão (hoje o vínculo é com cliente/processo, cujos documentos ficam no repositório), lembretes automáticos de vencimento dos cartões e quadros-modelo criados pelo próprio escritório.
* CRM: captura automática de leads (formulário do site/WhatsApp) e envio de e-mail ao lead dependem de integração externa.
* Módulos das etapas 12–14 (rotas aparecem desabilitadas na sidebar com a etapa indicada).
* Assinatura eletrônica com certificado ICP-Brasil e antivírus nos uploads.
* Financeiro: DRE em regime de competência, conciliação bancária (OFX) e emissão de boletos/Pix dependem de integração com instituição financeira.
* Exportação PDF/Excel dos relatórios financeiros (CSV já disponível) entra com a central de relatórios.
* Calendários forenses estaduais/municipais pré-carregados (hoje cadastrados pelo escritório em *Prazos → Calendário forense*).
* Regras especiais de contagem não presumidas: prazo em dobro (CPC, arts. 180, 183, 186 e 229), intimação eletrônica tácita e publicação no DJe — informadas pelo usuário.
* Envio push (FCM) exige projeto Firebase real e chave VAPID; no Emulator Suite as notificações chegam pelo sino em tempo real.
* Envio do convite de acesso à equipe pelo e-mail transacional (o SMTP da Fase 4 já está disponível; hoje o link é exibido ao administrador).
* Backup agendado do Firestore (`gcloud firestore export`) — documentado em `docs/SEGURANCA.md`.
