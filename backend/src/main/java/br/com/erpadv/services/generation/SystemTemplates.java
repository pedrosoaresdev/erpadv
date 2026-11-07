package br.com.erpadv.services.generation;

import br.com.erpadv.models.ContractEnums.ContractType;
import br.com.erpadv.models.ContractTemplate;

import java.util.List;

/**
 * Modelos de partida oferecidos pelo sistema (somente leitura; o escritório pode duplicá-los e
 * adaptá-los). Textos-base que DEVEM ser revisados pelo advogado antes do uso.
 */
public final class SystemTemplates {

    private SystemTemplates() {
    }

    public static final String HONORARIOS = """
            # CONTRATO DE PRESTAÇÃO DE SERVIÇOS ADVOCATÍCIOS E HONORÁRIOS Nº {{contrato.numero}}

            ## CONTRATANTE
            {{cliente.nome}}, {{cliente.qualificacao}}, inscrito(a) no {{cliente.tipoDocumento}} sob o nº {{cliente.documento}}, residente/sediado(a) em {{cliente.endereco}}, e-mail {{cliente.email}}.

            ## CONTRATADO
            {{escritorio.nome}}, inscrito no CNPJ sob o nº {{escritorio.cnpj}}, neste ato representado por {{advogado.nome}}, inscrito(a) na OAB sob o nº {{advogado.oab}}.

            ## CLÁUSULA 1ª — DO OBJETO
            O presente contrato tem por objeto a prestação de serviços advocatícios consistentes em: {{contrato.objeto}}. Processo de referência: {{processo.numero}}, em trâmite perante {{processo.vara}} da comarca de {{processo.comarca}}, em face de {{processo.parteContraria}}.

            ## CLÁUSULA 2ª — DOS HONORÁRIOS
            Pelos serviços contratados, o CONTRATANTE pagará ao CONTRATADO honorários na modalidade {{contrato.modeloHonorarios}}, no valor total de {{contrato.valor}} ({{contrato.valorExtenso}}), em {{contrato.parcelas}} parcela(s) de {{contrato.valorParcela}}, com primeiro vencimento em {{contrato.primeiroVencimento}}.

            Parágrafo único. Havendo êxito, serão devidos ainda honorários de {{contrato.percentualExito}} sobre {{contrato.baseExito}}, sem prejuízo dos honorários de sucumbência, que pertencem ao advogado, nos termos da Lei nº 8.906/1994 (Estatuto da Advocacia e da OAB).

            ## CLÁUSULA 3ª — DAS DESPESAS
            Custas processuais, emolumentos, perícias, deslocamentos e demais despesas necessárias ao andamento dos serviços correrão por conta do CONTRATANTE, mediante prévia comunicação.

            ## CLÁUSULA 4ª — DA VIGÊNCIA E RESCISÃO
            O presente contrato vigora a partir de {{contrato.inicio}}, podendo ser rescindido por qualquer das partes mediante comunicação escrita, sendo devidos os honorários proporcionais aos serviços já prestados.

            ## CLÁUSULA 5ª — DO SIGILO E DA PROTEÇÃO DE DADOS
            O CONTRATADO manterá sigilo sobre as informações recebidas, observando o sigilo profissional e a Lei nº 13.709/2018 (LGPD), utilizando os dados pessoais exclusivamente para a execução deste contrato.

            ## CLÁUSULA 6ª — DO FORO
            Fica eleito o foro da comarca de {{data.cidade}} para dirimir quaisquer controvérsias oriundas deste contrato.

            E, por estarem justas e contratadas, as partes assinam o presente instrumento.

            > {{data.cidade}}, {{data.hoje}}.

            > ____________________________________________
            > {{cliente.nome}} — CONTRATANTE

            > ____________________________________________
            > {{advogado.nome}} — OAB {{advogado.oab}} — CONTRATADO
            """;

    public static final String PROCURACAO = """
            # PROCURAÇÃO AD JUDICIA ET EXTRA

            ## OUTORGANTE
            {{cliente.nome}}, {{cliente.qualificacao}}, inscrito(a) no {{cliente.tipoDocumento}} sob o nº {{cliente.documento}}, residente/sediado(a) em {{cliente.endereco}}.

            ## OUTORGADO
            {{advogado.nome}}, advogado(a) inscrito(a) na OAB sob o nº {{advogado.oab}}, integrante de {{escritorio.nome}}, com endereço profissional em {{escritorio.cidade}}/{{escritorio.uf}}.

            ## PODERES
            Pelo presente instrumento, o(a) OUTORGANTE nomeia e constitui o(a) OUTORGADO(A) seu(sua) procurador(a), conferindo-lhe os poderes da cláusula ad judicia et extra para o foro em geral (CPC, art. 105), podendo propor ações, contestar, recorrer, transigir, desistir, receber citação, dar e receber quitação, firmar compromissos e substabelecer, com ou sem reserva de poderes, especialmente para: {{contrato.objeto}}.

            > {{data.cidade}}, {{data.hoje}}.

            > ____________________________________________
            > {{cliente.nome}}
            """;

    public static final String PARTIDO_MENSAL = """
            # CONTRATO DE PRESTAÇÃO DE SERVIÇOS JURÍDICOS — PARTIDO MENSAL Nº {{contrato.numero}}

            ## PARTES
            CONTRATANTE: {{cliente.nome}}, {{cliente.tipoDocumento}} nº {{cliente.documento}}, com endereço em {{cliente.endereco}}.
            CONTRATADO: {{escritorio.nome}}, CNPJ nº {{escritorio.cnpj}}, representado por {{advogado.nome}}, OAB {{advogado.oab}}.

            ## CLÁUSULA 1ª — DO OBJETO
            Prestação continuada de serviços de assessoria e consultoria jurídica compreendendo: {{contrato.objeto}}.

            ## CLÁUSULA 2ª — DA REMUNERAÇÃO
            O CONTRATANTE pagará mensalmente o valor de {{contrato.mensalidade}} ({{contrato.mensalidadeExtenso}}), pelo período de {{contrato.meses}} meses, com primeiro vencimento em {{contrato.primeiroVencimento}}.

            ## CLÁUSULA 3ª — DA VIGÊNCIA
            Vigência de {{contrato.inicio}} a {{contrato.termino}}, renovável mediante termo aditivo.

            ## CLÁUSULA 4ª — DO SIGILO
            As partes obrigam-se a manter sigilo sobre as informações trocadas, observadas as normas éticas da advocacia e a LGPD.

            > {{data.cidade}}, {{data.hoje}}.

            > ____________________________________________
            > {{cliente.nome}} — CONTRATANTE

            > ____________________________________________
            > {{advogado.nome}} — CONTRATADO
            """;

    public static List<ContractTemplate> all() {
        return List.of(
                template("system:honorarios", "Contrato de honorários advocatícios", ContractType.HONORARIOS,
                        "Honorários fixos, parcelados e/ou de êxito, com cláusulas de sigilo e LGPD.", HONORARIOS),
                template("system:procuracao", "Procuração ad judicia et extra", ContractType.PROCURACAO,
                        "Procuração geral para o foro com poderes especiais.", PROCURACAO),
                template("system:partido-mensal", "Partido mensal (assessoria contínua)", ContractType.PARTIDO_MENSAL,
                        "Assessoria jurídica recorrente com mensalidade.", PARTIDO_MENSAL));
    }

    private static ContractTemplate template(String id, String name, ContractType type, String description, String body) {
        ContractTemplate t = new ContractTemplate();
        t.setId(id);
        t.setName(name);
        t.setType(type);
        t.setDescription(description);
        t.setBody(body);
        t.setSystem(true);
        t.setActive(true);
        return t;
    }
}
