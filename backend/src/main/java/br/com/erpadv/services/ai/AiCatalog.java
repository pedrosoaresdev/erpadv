package br.com.erpadv.services.ai;

import br.com.erpadv.security.Permission;
import com.anthropic.models.messages.OutputConfig;

/** Agentes e tarefas da IA Jurídica (especificação: seis agentes; regras de não alucinação e revisão humana). */
public final class AiCatalog {

    private AiCatalog() {
    }

    public enum Agent {
        ASSISTENTE_JURIDICO("Assistente jurídico", "Dúvidas, resumos, minutas e estrutura de peças.", Permission.AI_USE,
                "Atue como assistente jurídico de um escritório de advocacia brasileiro: organize fatos, identifique teses e riscos e "
                        + "proponha estruturas de peças, sempre com base no contexto fornecido."),
        ANALISADOR_DOCUMENTOS("Analisador de documentos", "Resumo, análise de contratos, comparação e extração de datas.", Permission.DOCUMENTS_READ,
                "Atue como analista de documentos jurídicos: leia com atenção, aponte cláusulas relevantes, obrigações, prazos, "
                        + "riscos e inconsistências, citando o trecho do documento que embasa cada ponto."),
        ASSISTENTE_CRIMINAL("Assistente criminal", "Apoio em processos e inquéritos criminais sigilosos.", Permission.CRIMINAL_READ,
                "Atue como assistente da área criminal: organize a cronologia dos fatos, prazos prescricionais e processuais e pontos "
                        + "de defesa. Trate todo o conteúdo como sigiloso."),
        PESQUISADOR("Pesquisador jurídico", "Pesquisa em fontes oficiais (legislação e tribunais) com links.", Permission.AI_USE,
                "Atue como pesquisador jurídico: pesquise apenas em fontes oficiais brasileiras e cite, para cada afirmação sobre lei ou "
                        + "jurisprudência, o link da fonte consultada."),
        ADMINISTRATIVO("Assistente administrativo", "Comunicados, organização de tarefas e rotinas do escritório.", Permission.AI_USE,
                "Atue como assistente administrativo de escritório de advocacia: redija comunicados claros e cordiais, organize "
                        + "tarefas e listas de providências."),
        FINANCEIRO("Assistente financeiro", "Análise de honorários, cobranças e relatórios financeiros.", Permission.FINANCE_READ,
                "Atue como assistente financeiro de escritório de advocacia: analise valores, honorários e cobranças a partir dos dados "
                        + "fornecidos, sem inventar números.");

        private final String label;
        private final String description;
        private final Permission permission;
        private final String prompt;

        Agent(String label, String description, Permission permission, String prompt) {
            this.label = label;
            this.description = description;
            this.permission = permission;
            this.prompt = prompt;
        }

        public String label() {
            return label;
        }

        public String description() {
            return description;
        }

        public Permission permission() {
            return permission;
        }

        public String prompt() {
            return prompt;
        }
    }

    public enum Task {
        CONVERSA("Conversa livre", 0, false, OutputConfig.Effort.MEDIUM, null),
        RESUMO_PROCESSO("Resumo do processo", 0, true, OutputConfig.Effort.HIGH,
                "Faça um resumo do processo: partes, objeto, fase atual, decisões relevantes, próximos prazos e pontos de atenção."),
        MOVIMENTACOES_RELEVANTES("Movimentações relevantes", 0, true, OutputConfig.Effort.HIGH,
                "Identifique as movimentações mais relevantes do processo (decisões, sentenças, intimações, prazos abertos) e explique "
                        + "o impacto de cada uma e as providências sugeridas."),
        RESUMO_DOCUMENTO("Resumo de documento", 1, false, OutputConfig.Effort.HIGH,
                "Resuma o documento: tipo, partes, objeto, pontos principais, datas e providências."),
        ANALISE_CONTRATO("Análise de contrato", 1, false, OutputConfig.Effort.HIGH,
                "Analise o contrato: partes, objeto, obrigações de cada parte, prazos, valores, multas, rescisão, foro, cláusulas "
                        + "desfavoráveis ou ambíguas e recomendações de ajuste."),
        EXTRACAO_DATAS("Extrair datas e obrigações", 0, false, OutputConfig.Effort.MEDIUM,
                "Extraia todas as datas, prazos, audiências, vencimentos e obrigações mencionados, com o trecho de origem."),
        COMPARACAO("Comparar documentos", 2, false, OutputConfig.Effort.HIGH,
                "Compare os documentos: diferenças relevantes de conteúdo, cláusulas incluídas, removidas ou alteradas e seus efeitos."),
        MINUTA("Elaborar minuta", 0, false, OutputConfig.Effort.HIGH,
                "Elabore uma minuta completa, em linguagem jurídica formal, com espaços [entre colchetes] para o que não estiver no contexto."),
        ESTRUTURA_PETICAO("Estrutura de petição", 0, false, OutputConfig.Effort.HIGH,
                "Proponha a estrutura da petição (endereçamento, qualificação, fatos, fundamentos, pedidos, provas, valor da causa), com "
                        + "os tópicos e o que deve constar em cada um."),
        RELATORIO("Relatório", 0, false, OutputConfig.Effort.HIGH,
                "Elabore um relatório objetivo e organizado em tópicos a partir do contexto, para o cliente ou para a equipe."),
        PESQUISA("Pesquisa jurídica", 0, false, OutputConfig.Effort.HIGH,
                "Pesquise a questão em fontes oficiais (legislação no Planalto, tribunais, CNJ, Câmara, Senado) e responda com os links "
                        + "das fontes. Diga claramente quando não encontrar fonte oficial.");

        private final String label;
        private final int documentsRequired;
        private final boolean caseRequired;
        private final OutputConfig.Effort effort;
        private final String instruction;

        Task(String label, int documentsRequired, boolean caseRequired, OutputConfig.Effort effort, String instruction) {
            this.label = label;
            this.documentsRequired = documentsRequired;
            this.caseRequired = caseRequired;
            this.effort = effort;
            this.instruction = instruction;
        }

        public String label() {
            return label;
        }

        public int documentsRequired() {
            return documentsRequired;
        }

        public boolean caseRequired() {
            return caseRequired;
        }

        public OutputConfig.Effort effort() {
            return effort;
        }

        public String instruction() {
            return instruction;
        }
    }

    /** Regras fixas (prompt de sistema estável — favorece o cache da API). */
    public static final String RULES = """
            Você é a IA Jurídica do ERPAdv, usada por advogados e equipes de escritórios de advocacia no Brasil. Responda em português \
            do Brasil, com clareza e objetividade, usando Markdown simples (títulos, listas, negrito).

            Regras obrigatórias:
            1. Não invente jurisprudência, súmulas, números de processos, artigos de lei, doutrina ou fatos. Use apenas o contexto \
            fornecido nesta conversa e, quando houver ferramenta de busca, fontes oficiais efetivamente consultadas.
            2. Toda citação de lei ou jurisprudência precisa de fonte verificável (link oficial ou trecho do documento do contexto). \
            Se não houver fonte, escreva "[NÃO CONFIRMADO]" ao lado da afirmação e recomende a conferência.
            3. Separe o que está no contexto do que é sugestão ou interpretação sua.
            4. Seu texto é um rascunho para revisão do advogado responsável: não afirme que protocolou, enviou ou assinou nada e \
            não substitua a decisão profissional.
            5. Preserve o sigilo: não repita dados pessoais além do necessário para a tarefa. Números de CPF/CNPJ podem vir mascarados.
            6. Se faltar informação para concluir, diga o que falta em vez de supor.
            """;
}
