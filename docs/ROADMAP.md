# Planejamento por etapas

Legenda: ✅ concluída · 🟡 parcial · ⏳ pendente

| Etapa | Escopo | Status |
|---|---|---|
| 1 | Arquitetura, modelagem Firestore, fluxo de autenticação, estrutura de pastas | ✅ |
| 2 | Firebase: `firebase.json`, Security Rules (Firestore/Storage), índices, Emulator Suite, testes de rules | ✅ |
| 3 | Backend Java: Firebase Admin, segurança, RBAC, auditoria encadeada, criptografia, paginação, tratamento de erros, logs | ✅ |
| 4 | Frontend Angular: layout premium, tema claro/escuro, sidebar recolhível, configuração em runtime | ✅ |
| 5 | Autenticação e permissões: login, cadastro do escritório, recuperação de senha, verificação de e-mail, convites, bloqueio, revogação de sessões | ✅ |
| 6 | Dashboard executivo (clientes e processos; indicadores financeiros e de prazos entram com as etapas 9 e 10) | 🟡 |
| 7 | CRM de clientes (cadastro PF/PJ, pesquisa, inativação, atendimentos, página do cliente) | ✅ |
| 8 | Gestão processual (cadastro, arquivamento, movimentações, decisões, despachos, linha do tempo, filtros) | ✅ |
| 9 | Prazos (alertas 10/5/3/1/0 dias via FCM) e agenda (dia/semana/mês) | ⏳ |
| 10 | Financeiro (receber/pagar, fluxo de caixa, DRE, inadimplência) | ⏳ |
| 11 | Contratos/honorários e gestão documental (Storage, versões, log de acesso, PDF/DOCX) | ⏳ |
| 12 | Departamento criminal (custodiados, inquéritos, cautelares) | ⏳ |
| 13 | IA jurídica (6 agentes via backend, regras de não-alucinação, revisão humana) | ⏳ |
| 14 | Portal do cliente | ⏳ |
| 15 | Painel Master / TI | 🟡 (auditoria e gestão de usuários prontas) |
| 16 | Docker, Nginx (HTTPS), Compose com Emulator Suite | ✅ |
| 17 | Testes e segurança (ampliação contínua a cada etapa) | 🟡 |

## O que falta (registrado ao fim desta entrega)

* Módulos das etapas 9–14 (rotas aparecem desabilitadas na sidebar com a etapa indicada).
* Envio de e-mail transacional para convites (hoje o link é exibido ao administrador).
* Integração com consulta processual oficial (ex.: DataJud/CNJ) — interface preparada em
  `CaseMovement.source`; depende de credencial e termos de uso.
* Backup agendado do Firestore (`gcloud firestore export`) — documentado em `docs/SEGURANCA.md`.
