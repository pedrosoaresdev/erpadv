package br.com.erpadv.services.generation;

import br.com.erpadv.models.LegalDocKind;

import java.util.List;
import java.util.Optional;

/**
 * Modelos de partida do Editor Jurídico (somente leitura; o escritório pode duplicá-los). São estruturas-base:
 * trechos entre colchetes devem ser redigidos pelo advogado e todo o conteúdo deve ser revisado antes do uso.
 * Campos {@code {{grupo.campo}}} são preenchidos com os dados do cliente, processo, escritório e advogado.
 */
public final class LegalTemplates {

    public record SystemTemplate(String id, LegalDocKind kind, String name, String description, String content) {
    }

    private LegalTemplates() {
    }

    private static final String QUALIFICACAO = "<strong>{{cliente.nome}}</strong>, {{cliente.qualificacao}}, inscrito(a) no "
            + "{{cliente.tipoDocumento}} sob o nº {{cliente.documento}}, residente e domiciliado(a) em {{cliente.endereco}}, "
            + "endereço eletrônico {{cliente.email}}";

    private static final String ENDERECAMENTO = "<p style=\"text-align: left;\"><strong>EXCELENTÍSSIMO(A) SENHOR(A) DOUTOR(A) JUIZ(A) DE DIREITO DA "
            + "{{processo.vara}} DA COMARCA DE {{processo.comarca}}</strong></p><p><br></p>";

    private static final String PROCESSO_REF = "<p style=\"text-align: left;\">Processo nº {{processo.numero}}</p><p><br></p>";

    private static final String FECHO = "<p>Nestes termos, pede deferimento.</p>"
            + "<p style=\"text-align: right;\">{{data.cidade}}, {{data.hoje}}.</p><p><br></p>"
            + "<p style=\"text-align: center;\">_______________________________________<br>{{advogado.nome}}<br>OAB {{advogado.oab}}</p>";

    private static final List<SystemTemplate> ALL = List.of(
            new SystemTemplate("sys-procuracao", LegalDocKind.PROCURACAO, "Procuração ad judicia et extra",
                    "Outorga de poderes gerais e especiais ao advogado.",
                    "<h1>PROCURAÇÃO AD JUDICIA ET EXTRA</h1>"
                            + "<p><strong>OUTORGANTE:</strong> " + QUALIFICACAO + ".</p>"
                            + "<p><strong>OUTORGADO(S):</strong> {{advogado.nome}}, advogado(a) inscrito(a) na OAB sob o nº {{advogado.oab}}, "
                            + "integrante de {{escritorio.nome}}, com endereço profissional em {{escritorio.cidade}}/{{escritorio.uf}}, onde recebe intimações.</p>"
                            + "<p><strong>PODERES:</strong> pelo presente instrumento, o(a) outorgante confere ao(s) outorgado(s) amplos poderes para o foro em geral, "
                            + "com a cláusula <em>ad judicia et extra</em>, em qualquer juízo, instância ou tribunal, podendo propor as ações competentes e defender "
                            + "o(a) outorgante nas contrárias, seguindo umas e outras até final decisão, usando os recursos legais e acompanhando-os, conferindo-lhe(s), "
                            + "ainda, poderes especiais para confessar, desistir, transigir, firmar compromissos ou acordos, receber e dar quitação, "
                            + "[ajustar poderes especiais conforme o caso], podendo ainda substabelecer esta a outrem, com ou sem reservas de iguais poderes.</p>"
                            + "<p><strong>FINALIDADE:</strong> [descrever a finalidade específica, se houver].</p><p><br></p>"
                            + "<p style=\"text-align: right;\">{{data.cidade}}, {{data.hoje}}.</p><p><br></p>"
                            + "<p style=\"text-align: center;\">_______________________________________<br>{{cliente.nome}}</p>"),
            new SystemTemplate("sys-honorarios", LegalDocKind.CONTRATO_HONORARIOS, "Contrato de honorários advocatícios",
                    "Contrato de prestação de serviços com cláusulas essenciais.",
                    "<h1>CONTRATO DE PRESTAÇÃO DE SERVIÇOS ADVOCATÍCIOS</h1>"
                            + "<p><strong>CONTRATANTE:</strong> " + QUALIFICACAO + ".</p>"
                            + "<p><strong>CONTRATADO:</strong> {{escritorio.nome}}, inscrito no CNPJ sob o nº {{escritorio.cnpj}}, neste ato representado por "
                            + "{{advogado.nome}}, OAB {{advogado.oab}}.</p>"
                            + "<h2>CLÁUSULA 1ª — DO OBJETO</h2><p>O presente contrato tem por objeto a prestação de serviços advocatícios consistentes em "
                            + "[descrever o objeto].</p>"
                            + "<h2>CLÁUSULA 2ª — DOS HONORÁRIOS</h2><p>Pelos serviços, o CONTRATANTE pagará ao CONTRATADO o valor de [valor e forma de pagamento], "
                            + "sem prejuízo dos honorários de sucumbência, que pertencem ao advogado (art. 23 da Lei nº 8.906/1994).</p>"
                            + "<h2>CLÁUSULA 3ª — DAS DESPESAS</h2><p>Custas, emolumentos e despesas processuais correrão por conta do CONTRATANTE.</p>"
                            + "<h2>CLÁUSULA 4ª — DA RESCISÃO</h2><p>[Condições de rescisão e honorários proporcionais.]</p>"
                            + "<h2>CLÁUSULA 5ª — DO FORO</h2><p>Fica eleito o foro da comarca de {{escritorio.cidade}}/{{escritorio.uf}}.</p><p><br></p>"
                            + "<p style=\"text-align: right;\">{{data.cidade}}, {{data.hoje}}.</p><p><br></p>"
                            + "<p style=\"text-align: center;\">_______________________________________<br>{{cliente.nome}} — CONTRATANTE</p><p><br></p>"
                            + "<p style=\"text-align: center;\">_______________________________________<br>{{escritorio.nome}} — CONTRATADO</p>"),
            new SystemTemplate("sys-inicial", LegalDocKind.PETICAO_INICIAL, "Petição inicial",
                    "Estrutura com endereçamento, qualificação, fatos, direito, pedidos e valor da causa.",
                    ENDERECAMENTO
                            + "<p>" + QUALIFICACAO + ", por seu(sua) advogado(a) que esta subscreve (procuração anexa), vem, respeitosamente, à presença de "
                            + "Vossa Excelência, propor a presente</p>"
                            + "<h1>[NOME DA AÇÃO]</h1>"
                            + "<p>em face de <strong>{{processo.parteContraria}}</strong>, [qualificação da parte ré], pelos fatos e fundamentos a seguir expostos.</p>"
                            + "<h2>I — DA GRATUIDADE DA JUSTIÇA</h2><p>[Se for o caso, requerer com base no art. 98 do CPC e juntar declaração.]</p>"
                            + "<h2>II — DOS FATOS</h2><p>[Narrar os fatos de forma clara e cronológica.]</p>"
                            + "<h2>III — DO DIREITO</h2><p>[Fundamentação jurídica.]</p>"
                            + "<h2>IV — DOS PEDIDOS</h2><p>Diante do exposto, requer:</p>"
                            + "<ol><li>a citação da parte ré para, querendo, apresentar resposta;</li><li>[pedidos];</li>"
                            + "<li>a condenação da parte ré ao pagamento das custas e honorários advocatícios.</li></ol>"
                            + "<p>Protesta provar o alegado por todos os meios de prova admitidos em direito.</p>"
                            + "<p>Dá-se à causa o valor de {{processo.valorCausa}}.</p>" + FECHO),
            new SystemTemplate("sys-contestacao", LegalDocKind.CONTESTACAO, "Contestação",
                    "Defesa com preliminares, mérito e pedidos.",
                    ENDERECAMENTO + PROCESSO_REF
                            + "<p>" + QUALIFICACAO + ", já qualificado(a) nos autos da ação movida por <strong>{{processo.parteContraria}}</strong>, "
                            + "por seu(sua) advogado(a), vem, tempestivamente, apresentar</p><h1>CONTESTAÇÃO</h1><p>pelas razões a seguir.</p>"
                            + "<h2>I — SÍNTESE DA INICIAL</h2><p>[Resumo das alegações do autor.]</p>"
                            + "<h2>II — PRELIMINARES</h2><p>[Art. 337 do CPC, se aplicável.]</p>"
                            + "<h2>III — DO MÉRITO</h2><p>[Impugnação específica dos fatos — art. 341 do CPC.]</p>"
                            + "<h2>IV — DOS PEDIDOS</h2><ol><li>o acolhimento das preliminares;</li><li>no mérito, a total improcedência dos pedidos;</li>"
                            + "<li>a condenação da parte autora nos ônus da sucumbência.</li></ol>" + FECHO),
            new SystemTemplate("sys-replica", LegalDocKind.REPLICA, "Réplica (impugnação à contestação)",
                    "Manifestação sobre a contestação e documentos.",
                    ENDERECAMENTO + PROCESSO_REF
                            + "<p>" + QUALIFICACAO + ", nos autos em epígrafe, vem apresentar</p><h1>RÉPLICA</h1>"
                            + "<p>à contestação apresentada por <strong>{{processo.parteContraria}}</strong>, nos termos dos arts. 350 e 351 do CPC.</p>"
                            + "<h2>I — DAS PRELIMINARES ARGUIDAS</h2><p>[Rebater as preliminares.]</p>"
                            + "<h2>II — DO MÉRITO</h2><p>[Rebater os argumentos de mérito e os documentos juntados.]</p>"
                            + "<h2>III — DOS REQUERIMENTOS</h2><p>Reitera os pedidos da inicial e requer [produção de provas].</p>" + FECHO),
            new SystemTemplate("sys-recurso", LegalDocKind.RECURSO, "Recurso (interposição e razões)",
                    "Peça de interposição com razões recursais.",
                    ENDERECAMENTO + PROCESSO_REF
                            + "<p>" + QUALIFICACAO + ", inconformado(a) com a decisão proferida, vem interpor</p><h1>[ESPÉCIE DO RECURSO]</h1>"
                            + "<p>requerendo o seu recebimento e regular processamento, com a remessa ao Tribunal competente. [Comprovar preparo, se devido.]</p>"
                            + FECHO
                            + "<hr class=\"page-break\">"
                            + "<h1>RAZÕES RECURSAIS</h1><p>Recorrente: {{cliente.nome}}<br>Recorrido(a): {{processo.parteContraria}}<br>Processo de origem: {{processo.numero}}</p>"
                            + "<h2>I — DA TEMPESTIVIDADE E DO CABIMENTO</h2><p>[Demonstrar.]</p>"
                            + "<h2>II — SÍNTESE DA DECISÃO RECORRIDA</h2><p>[Resumo.]</p>"
                            + "<h2>III — DAS RAZÕES PARA A REFORMA</h2><p>[Fundamentação.]</p>"
                            + "<h2>IV — DO PEDIDO</h2><p>Requer o conhecimento e o provimento do recurso para [pedido].</p>" + FECHO),
            new SystemTemplate("sys-contrarrazoes", LegalDocKind.CONTRARRAZOES, "Contrarrazões",
                    "Resposta ao recurso da parte contrária.",
                    ENDERECAMENTO + PROCESSO_REF
                            + "<p>" + QUALIFICACAO + ", nos autos em epígrafe, vem apresentar</p><h1>CONTRARRAZÕES</h1>"
                            + "<p>ao recurso interposto por <strong>{{processo.parteContraria}}</strong>, requerendo a remessa ao Tribunal.</p>" + FECHO
                            + "<hr class=\"page-break\">"
                            + "<h1>CONTRARRAZÕES DE RECURSO</h1>"
                            + "<h2>I — PRELIMINARMENTE: DO NÃO CONHECIMENTO</h2><p>[Se aplicável.]</p>"
                            + "<h2>II — DO MÉRITO</h2><p>[Defesa da decisão recorrida.]</p>"
                            + "<h2>III — CONCLUSÃO</h2><p>Requer o não provimento do recurso, mantendo-se a decisão recorrida.</p>" + FECHO),
            new SystemTemplate("sys-hipossuficiencia", LegalDocKind.DECLARACAO_HIPOSSUFICIENCIA, "Declaração de hipossuficiência",
                    "Declaração para pedido de gratuidade da justiça.",
                    "<h1>DECLARAÇÃO DE HIPOSSUFICIÊNCIA ECONÔMICA</h1>"
                            + "<p>" + QUALIFICACAO + ", <strong>DECLARA</strong>, para os fins do art. 98 e seguintes do Código de Processo Civil e sob as penas "
                            + "da lei, que não possui condições de arcar com as custas processuais e os honorários advocatícios sem prejuízo do próprio sustento "
                            + "e de sua família.</p>"
                            + "<p>Declara, ainda, estar ciente de que a falsidade desta declaração pode configurar crime e sujeitá-lo(a) às sanções cabíveis.</p><p><br></p>"
                            + "<p style=\"text-align: right;\">{{data.cidade}}, {{data.hoje}}.</p><p><br></p>"
                            + "<p style=\"text-align: center;\">_______________________________________<br>{{cliente.nome}}</p>"),
            new SystemTemplate("sys-substabelecimento", LegalDocKind.SUBSTABELECIMENTO, "Substabelecimento",
                    "Transferência de poderes com ou sem reserva.",
                    "<h1>SUBSTABELECIMENTO</h1>"
                            + "<p><strong>SUBSTABELECENTE:</strong> {{advogado.nome}}, advogado(a) inscrito(a) na OAB sob o nº {{advogado.oab}}.</p>"
                            + "<p><strong>SUBSTABELECIDO(A):</strong> [nome do(a) advogado(a)], OAB [número].</p>"
                            + "<p>Pelo presente instrumento, o(a) substabelecente substabelece, [com / sem] reserva de iguais poderes, os poderes que lhe foram "
                            + "conferidos por <strong>{{cliente.nome}}</strong> nos autos do processo nº {{processo.numero}}, em trâmite perante "
                            + "{{processo.vara}} da comarca de {{processo.comarca}}.</p><p><br></p>"
                            + "<p style=\"text-align: right;\">{{data.cidade}}, {{data.hoje}}.</p><p><br></p>"
                            + "<p style=\"text-align: center;\">_______________________________________<br>{{advogado.nome}}<br>OAB {{advogado.oab}}</p>"),
            new SystemTemplate("sys-notificacao", LegalDocKind.NOTIFICACAO_EXTRAJUDICIAL, "Notificação extrajudicial",
                    "Notificação formal com prazo para cumprimento.",
                    "<h1>NOTIFICAÇÃO EXTRAJUDICIAL</h1>"
                            + "<p><strong>NOTIFICANTE:</strong> " + QUALIFICACAO + ", por seu(sua) advogado(a) {{advogado.nome}}, OAB {{advogado.oab}}.</p>"
                            + "<p><strong>NOTIFICADO(A):</strong> [nome e qualificação], [endereço].</p>"
                            + "<p>Pela presente, fica V. Sa. <strong>NOTIFICADO(A)</strong> para que, no prazo de [prazo] dias a contar do recebimento, "
                            + "[obrigação a cumprir], em razão de [fatos].</p>"
                            + "<p>O não atendimento ensejará a adoção das medidas judiciais cabíveis, sem prejuízo de perdas e danos.</p><p><br></p>"
                            + "<p style=\"text-align: right;\">{{data.cidade}}, {{data.hoje}}.</p><p><br></p>"
                            + "<p style=\"text-align: center;\">_______________________________________<br>{{advogado.nome}}<br>OAB {{advogado.oab}}</p>"),
            new SystemTemplate("sys-requerimento", LegalDocKind.REQUERIMENTO, "Requerimento simples (petição intermediária)",
                    "Petição de juntada, manifestação ou pedido simples nos autos.",
                    ENDERECAMENTO + PROCESSO_REF
                            + "<p>" + QUALIFICACAO + ", nos autos da ação em epígrafe movida em face de {{processo.parteContraria}}, vem, respeitosamente, "
                            + "requerer [objeto do requerimento], pelos motivos a seguir.</p><p>[Fundamentação breve.]</p>" + FECHO),
            new SystemTemplate("sys-parecer", LegalDocKind.PARECER, "Parecer jurídico",
                    "Consulta, análise e conclusão.",
                    "<h1>PARECER JURÍDICO</h1>"
                            + "<p><strong>Consulente:</strong> {{cliente.nome}}<br><strong>Assunto:</strong> [assunto]<br><strong>Parecerista:</strong> "
                            + "{{advogado.nome}}, OAB {{advogado.oab}}</p>"
                            + "<h2>I — DA CONSULTA</h2><p>[Descrição da questão submetida.]</p>"
                            + "<h2>II — DA ANÁLISE</h2><p>[Legislação, doutrina e jurisprudência aplicáveis.]</p>"
                            + "<h2>III — CONCLUSÃO</h2><p>[Conclusão e recomendações.]</p>"
                            + "<p>É o parecer, salvo melhor juízo.</p>"
                            + "<p style=\"text-align: right;\">{{data.cidade}}, {{data.hoje}}.</p><p><br></p>"
                            + "<p style=\"text-align: center;\">_______________________________________<br>{{advogado.nome}}<br>OAB {{advogado.oab}}</p>"),
            new SystemTemplate("sys-relatorio", LegalDocKind.RELATORIO_PROCESSUAL, "Relatório processual ao cliente",
                    "Situação do processo em linguagem acessível.",
                    "<h1>RELATÓRIO PROCESSUAL</h1>"
                            + "<table><tbody><tr><th>Cliente</th><td>{{cliente.nome}}</td></tr><tr><th>Processo</th><td>{{processo.numero}}</td></tr>"
                            + "<tr><th>Órgão</th><td>{{processo.vara}} — {{processo.comarca}}</td></tr><tr><th>Classe / assunto</th><td>{{processo.classe}} — {{processo.assunto}}</td></tr>"
                            + "<tr><th>Parte contrária</th><td>{{processo.parteContraria}}</td></tr><tr><th>Data</th><td>{{data.hoje}}</td></tr></tbody></table>"
                            + "<h2>1. Situação atual</h2><p>[Fase do processo e último andamento relevante.]</p>"
                            + "<h2>2. Próximos passos</h2><p>[Prazos, audiências e providências.]</p>"
                            + "<h2>3. Riscos e perspectivas</h2><p>[Avaliação.]</p>"
                            + "<h2>4. Pendências do cliente</h2><p>[Documentos ou informações necessárias.]</p><p><br></p>"
                            + "<p style=\"text-align: center;\">{{advogado.nome}}<br>OAB {{advogado.oab}} — {{escritorio.nome}}</p>"),
            new SystemTemplate("sys-branco", LegalDocKind.OUTRO, "Documento em branco",
                    "Comece do zero, com cabeçalho e rodapé do escritório.",
                    "<p><br></p>")
    );

    public static List<SystemTemplate> all() {
        return ALL;
    }

    public static Optional<SystemTemplate> find(String id) {
        return ALL.stream().filter(t -> t.id().equals(id)).findFirst();
    }
}
