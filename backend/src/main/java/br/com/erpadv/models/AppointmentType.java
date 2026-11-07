package br.com.erpadv.models;

public enum AppointmentType {
    AUDIENCIA("Audiência"),
    REUNIAO("Reunião"),
    CONSULTA("Consulta"),
    JULGAMENTO("Julgamento"),
    SUSTENTACAO_ORAL("Sustentação oral"),
    COMPROMISSO("Compromisso"),
    ATIVIDADE_INTERNA("Atividade interna");

    private final String label;

    AppointmentType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
