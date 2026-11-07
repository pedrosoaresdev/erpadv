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
| 9 | Prazos (contagem CPC com feriados, recesso e suspensões; validação pelo advogado; alertas 10/5/3/1/0 dias via notificação e FCM) e agenda (dia/semana/mês, lembretes) | ✅ |
| 10 | Financeiro (receber/pagar, parcelamentos, recorrências, baixa com comprovante, fluxo de caixa, projeções, DRE gerencial, inadimplência integrada ao CRM, CSV, finanças pessoais dos sócios) | ✅ |
| 11 | Contratos/honorários (fixo, parcelado, etapas, êxito, mensal → financeiro), modelos com campos dinâmicos, minuta PDF/DOCX, contrato assinado, histórico; gestão documental (Storage, versões, sigilo, histórico de acesso, pré-visualização) | ✅ |
| 12 | Departamento criminal (custodiados, inquéritos, cautelares) | ⏳ |
| 13 | IA jurídica (6 agentes via backend, regras de não-alucinação, revisão humana) | ⏳ |
| 14 | Portal do cliente | ⏳ |
| 15 | Painel Master / TI | 🟡 (painel da plataforma, cargos personalizados, auditoria e gestão de usuários prontos; relatórios pendentes) |
| + | Quadros Kanban jurídicos (WIP, automações, indicadores, cartão → prazo) e CRM comercial (funil, pontuação, conversão em cliente, métricas) | ✅ |
| 16 | Docker, Nginx (HTTPS), Compose com Emulator Suite | ✅ |
| 17 | Testes e segurança (ampliação contínua a cada etapa) | 🟡 |

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
