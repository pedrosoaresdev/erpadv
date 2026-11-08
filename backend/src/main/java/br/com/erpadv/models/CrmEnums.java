package br.com.erpadv.models;

/** Enumerações do CRM comercial (funil de captação de clientes). */
public final class CrmEnums {

    private CrmEnums() {
    }

    public enum LeadStage {
        NOVO("Novo contato", 10, true),
        CONTATO("Primeiro atendimento", 20, true),
        QUALIFICADO("Qualificação", 30, true),
        REUNIAO_AGENDADA("Reunião agendada", 45, true),
        PROPOSTA("Proposta enviada", 60, true),
        NEGOCIACAO("Aguardando decisão", 70, true),
        CONTRATO_ENVIADO("Contrato enviado", 85, true),
        GANHO("Cliente convertido", 100, false),
        PERDIDO("Atendimento encerrado", 0, false);

        private final String label;
        private final int probability;
        private final boolean open;

        LeadStage(String label, int probability, boolean open) {
            this.label = label;
            this.probability = probability;
            this.open = open;
        }

        public String label() {
            return label;
        }

        public int probability() {
            return probability;
        }

        public boolean isOpen() {
            return open;
        }
    }

    public enum LeadSource {
        INDICACAO("Indicação"),
        SITE("Site"),
        GOOGLE("Google"),
        INSTAGRAM("Instagram"),
        FACEBOOK("Facebook"),
        LINKEDIN("LinkedIn"),
        WHATSAPP("WhatsApp"),
        EVENTO("Evento/palestra"),
        CLIENTE_ATUAL("Cliente atual"),
        OUTRO("Outro");

        private final String label;

        LeadSource(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum LeadActivityType {
        NOTA, LIGACAO, EMAIL, REUNIAO, WHATSAPP, PROPOSTA, ETAPA, CONVERSAO
    }
}
