package br.com.erpadv.models;

/** Situação do compromisso (audiências: realizada, redesignada, cancelada…). */
public enum AppointmentStatus {
    AGENDADO("Agendado"),
    REALIZADO("Realizado"),
    REDESIGNADO("Redesignado"),
    ADIADO("Adiado"),
    CANCELADO("Cancelado");

    private final String label;

    AppointmentStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
