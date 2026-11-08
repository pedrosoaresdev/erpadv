package br.com.erpadv.models;

import java.net.URI;
import java.util.Locale;

/** Plataforma da reunião escolhida pelo advogado. O link é validado pelo domínio oficial de cada serviço. */
public enum MeetingPlatform {
    GOOGLE_MEET("Google Meet"),
    ZOOM("Zoom"),
    TEAMS("Microsoft Teams"),
    PRESENCIAL("Presencial"),
    OUTRO("Outro link");

    private final String label;

    MeetingPlatform(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Domínio do link compatível com a plataforma? (PRESENCIAL/OUTRO aceitam qualquer https). */
    public boolean accepts(String url) {
        String host = host(url);
        if (host == null) {
            return false;
        }
        return switch (this) {
            case GOOGLE_MEET -> host.equals("meet.google.com");
            case ZOOM -> host.equals("zoom.us") || host.endsWith(".zoom.us") || host.equals("zoomgov.com") || host.endsWith(".zoomgov.com");
            case TEAMS -> host.equals("teams.microsoft.com") || host.equals("teams.live.com");
            default -> true;
        };
    }

    /** Plataforma deduzida do link (para compromissos antigos ou sem escolha explícita). */
    public static MeetingPlatform fromUrl(String url) {
        String host = host(url);
        if (host == null) {
            return null;
        }
        for (MeetingPlatform p : new MeetingPlatform[]{GOOGLE_MEET, ZOOM, TEAMS}) {
            if (p.accepts(url)) {
                return p;
            }
        }
        return OUTRO;
    }

    private static String host(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            return uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
