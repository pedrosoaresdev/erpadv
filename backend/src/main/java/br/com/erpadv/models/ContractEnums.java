package br.com.erpadv.models;

/** Enumerações do módulo de contratos. */
public final class ContractEnums {

    private ContractEnums() {
    }

    public enum ContractStatus {
        RASCUNHO, ATIVO, ENCERRADO, CANCELADO
    }

    public enum ContractType {
        HONORARIOS("Contrato de honorários advocatícios"),
        PARTIDO_MENSAL("Prestação de serviços — partido mensal"),
        CONSULTORIA("Consultoria jurídica"),
        PROCURACAO("Procuração"),
        OUTRO("Outro");

        private final String label;

        ContractType(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Modelo de honorários; êxito pode ser combinado com qualquer modelo via {@code successPercentBp}. */
    public enum FeeModel {
        /** Valor único. */
        FIXO("Honorários fixos"),
        /** Valor dividido em parcelas mensais. */
        PARCELADO("Honorários parcelados"),
        /** Valores por etapas do processo, faturados conforme cada etapa é cumprida. */
        ETAPAS("Honorários por etapas"),
        /** Somente honorários de êxito (percentual sobre o proveito econômico). */
        EXITO("Honorários de êxito"),
        /** Partido mensal (valor recorrente). */
        MENSAL("Partido mensal");

        private final String label;

        FeeModel(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
