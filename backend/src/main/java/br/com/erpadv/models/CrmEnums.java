package br.com.erpadv.models;

/** Enumerações do CRM comercial (funil de captação de clientes). */
public final class CrmEnums {

    private CrmEnums() {
    }

    public enum LeadStage {
        NOVO("Novo", 10, true),
        CONTATO("Primeiro contato", 20, true),
        QUALIFICADO("Qualificado", 40, true),
        PROPOSTA("Proposta enviada", 60, true),
        NEGOCIACAO("Negociação", 80, true),
        GANHO("Ganho", 100, false),
        PERDIDO("Perdido", 0, false);

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
