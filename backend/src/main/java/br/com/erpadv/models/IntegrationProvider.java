package br.com.erpadv.models;

import java.util.List;

/**
 * Conectores da Central de Integrações. {@code availability} diz como o serviço é acessado: API pública oficial,
 * API oficial com credencial do escritório, ou sem API pública (somente importação assistida).
 */
public enum IntegrationProvider {
    DATAJUD("DataJud — API Pública do CNJ", "JUDICIAL", Availability.API_PUBLICA,
            "Consulta processual oficial do CNJ: dados do processo e movimentações de todos os tribunais (exceto processos em segredo).",
            List.of("apiKey")),
    DJEN("DJEN — Diário de Justiça Eletrônico Nacional", "JUDICIAL", Availability.API_PUBLICA,
            "Comunicações processuais (intimações) publicadas no DJEN, consultadas pelo número da OAB de cada advogado.", List.of()),
    PJE("PJe", "JUDICIAL", Availability.SEM_API,
            "O PJe oferece o MNI (SOAP) somente para entidades credenciadas por tribunal, com certificado digital. Use o DataJud e o DJEN ou a importação assistida.",
            List.of()),
    EPROC("eproc", "JUDICIAL", Availability.SEM_API, "Sem API pública para escritórios. Use o DataJud, o DJEN ou a importação assistida.", List.of()),
    PROJUDI("Projudi", "JUDICIAL", Availability.SEM_API, "Sem API pública para escritórios. Use o DataJud, o DJEN ou a importação assistida.", List.of()),
    ESAJ("e-SAJ", "JUDICIAL", Availability.SEM_API, "Sem API pública para escritórios. Use o DataJud, o DJEN ou a importação assistida.", List.of()),
    SMTP("E-mail (SMTP)", "COMUNICACAO", Availability.CREDENCIAL,
            "Envio de e-mails pelo servidor SMTP do escritório (Google Workspace, Microsoft 365, provedor próprio).",
            List.of("password")),
    WHATSAPP("WhatsApp Business (API oficial da Meta)", "COMUNICACAO", Availability.CREDENCIAL,
            "Envio de mensagens e recebimento por webhook pela WhatsApp Cloud API, com número verificado do escritório.",
            List.of("accessToken", "appSecret", "verifyToken")),
    GOOGLE_CALENDAR("Google Agenda e Google Meet", "AGENDA", Availability.CREDENCIAL,
            "Cada advogado conecta a própria conta Google: compromissos do ERPAdv vão para a agenda dele e reuniões do Meet recebem o link automaticamente.",
            List.of()),
    ZAPSIGN("Assinatura eletrônica (ZapSign)", "ASSINATURA", Availability.CREDENCIAL,
            "Envio de contratos, procurações e documentos para assinatura eletrônica com trilha de auditoria do provedor.",
            List.of("apiToken", "webhookSecret"));

    public enum Availability { API_PUBLICA, CREDENCIAL, SEM_API }

    private final String label;
    private final String category;
    private final Availability availability;
    private final String description;
    private final List<String> secretKeys;

    IntegrationProvider(String label, String category, Availability availability, String description, List<String> secretKeys) {
        this.label = label;
        this.category = category;
        this.availability = availability;
        this.description = description;
        this.secretKeys = secretKeys;
    }

    public String label() {
        return label;
    }

    public String category() {
        return category;
    }

    public Availability availability() {
        return availability;
    }

    public String description() {
        return description;
    }

    public List<String> secretKeys() {
        return secretKeys;
    }
}
