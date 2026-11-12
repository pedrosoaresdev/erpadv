package br.com.erpadv.services;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.models.Appointment;
import br.com.erpadv.models.AppointmentGuest;
import br.com.erpadv.models.Client;
import br.com.erpadv.models.Communication;
import br.com.erpadv.models.LegalCase;
import br.com.erpadv.models.MessageTemplate;
import br.com.erpadv.repositories.ClientRepository;
import br.com.erpadv.repositories.CommunicationRepositories;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.Permission;
import br.com.erpadv.services.generation.HtmlDocument;
import br.com.erpadv.services.integrations.EmailService;
import br.com.erpadv.services.integrations.WhatsAppService;
import com.google.cloud.firestore.Query;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Central de comunicação: e-mails e WhatsApp enviados pelo sistema, registro de contatos (ligações, atendimentos),
 * modelos de mensagem com campos automáticos, anexos da central de documentos, convites de reunião e lembretes de
 * retorno ao cliente. Tudo fica no histórico do cliente.
 */
@Service
public class CommunicationService {

    private static final Pattern FIELD = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.]+)\\s*}}");
    private static final long MAX_ATTACHMENTS_BYTES = 20L * 1024 * 1024;

    public record SendRequest(Communication.Channel channel, String clientId, String caseId, String to, String subject, String body,
                              String templateId, List<String> documentIds, LocalDate followUpDate, String whatsappTemplate,
                              List<String> whatsappParams) {
    }

    public record RegisterRequest(Communication.Channel channel, Communication.Direction direction, String clientId, String caseId,
                                  String subject, String body, LocalDate followUpDate) {
    }

    public record TemplateRequest(String name, Communication.Channel channel, String subject, String body, String whatsappTemplate) {
    }

    public record Rendered(String subject, String body, List<String> missing) {
    }

    private final CommunicationRepositories.Communications communications;
    private final CommunicationRepositories.Templates templates;
    private final ClientRepository clients;
    private final ClientService clientService;
    private final CaseService cases;
    private final LegalEditorService editor;
    private final DocumentService documents;
    private final CalendarService calendar;
    private final EmailService email;
    private final WhatsAppService whatsapp;
    private final NotificationService notifications;
    private final AuditService audit;

    public CommunicationService(CommunicationRepositories.Communications communications, CommunicationRepositories.Templates templates,
                                ClientRepository clients, ClientService clientService, CaseService cases, LegalEditorService editor,
                                DocumentService documents, CalendarService calendar, EmailService email, WhatsAppService whatsapp,
                                NotificationService notifications, AuditService audit) {
        this.communications = communications;
        this.templates = templates;
        this.clients = clients;
        this.clientService = clientService;
        this.cases = cases;
        this.editor = editor;
        this.documents = documents;
        this.calendar = calendar;
        this.email = email;
        this.whatsapp = whatsapp;
        this.notifications = notifications;
        this.audit = audit;
    }

    public Map<String, Boolean> channels(AuthenticatedUser user) {
        String officeId = user.requireOffice();
        return Map.of("EMAIL", email.available(officeId), "WHATSAPP", whatsapp.available(officeId));
    }

    // ------------------------------------------------------------------ consulta

    public List<Communication> list(AuthenticatedUser user, String clientId, String caseId, Communication.Channel channel, boolean followUps) {
        String officeId = user.requireOffice();
        Query q = communications.collection(officeId);
        if (caseId != null && !caseId.isBlank()) {
            cases.load(user, caseId);
            q = q.whereEqualTo("caseId", caseId);
        } else if (clientId != null && !clientId.isBlank()) {
            q = q.whereEqualTo("clientId", clientId);
        } else {
            q = q.orderBy("createdAt", Query.Direction.DESCENDING);
        }
        return communications.list(q, 500).stream()
                .filter(c -> !c.isRestricted() || user.has(Permission.CRIMINAL_READ))
                .filter(c -> channel == null || channel == c.getChannel())
                .filter(c -> !followUps || (c.getFollowUpDate() != null && !c.isFollowUpDone()))
                .sorted(followUps ? Comparator.comparing(Communication::getFollowUpDate)
                        : Comparator.comparing(Communication::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(300).toList();
    }

    // ------------------------------------------------------------------ envio

    public Communication send(AuthenticatedUser user, SendRequest req) {
        String officeId = user.requireOffice();
        if (req.channel() != Communication.Channel.EMAIL && req.channel() != Communication.Channel.WHATSAPP) {
            throw new BusinessException("INVALID_CHANNEL", "Envio disponível por e-mail ou WhatsApp; para os demais, registre o contato.");
        }
        Communication c = base(user, req.channel(), Communication.Direction.SAIDA, req.clientId(), req.caseId());
        Client client = c.getClientId() == null ? null : clients.findById(officeId, c.getClientId()).orElse(null);
        String subject = req.subject();
        String body = req.body();
        if (req.templateId() != null && !req.templateId().isBlank()) {
            MessageTemplate t = templates.findById(officeId, req.templateId()).orElseThrow(() -> new NotFoundException("Modelo"));
            c.setTemplateName(t.getName());
            if (body == null || body.isBlank()) {
                Rendered r = render(user, t.getId(), c.getClientId(), c.getCaseId());
                subject = r.subject();
                body = r.body();
            }
        }
        if (body == null || body.isBlank()) {
            throw new BusinessException("EMPTY_MESSAGE", "Escreva a mensagem.");
        }
        if (body.length() > 20000) {
            throw new BusinessException("MESSAGE_TOO_LONG", "Mensagem muito longa.");
        }
        c.setSubject(subject == null || subject.isBlank() ? null : subject.trim());
        c.setBody(body.trim());
        c.setFollowUpDate(req.followUpDate());
        try {
            if (req.channel() == Communication.Channel.EMAIL) {
                String to = req.to() != null && !req.to().isBlank() ? req.to().trim() : client == null ? null : client.getEmail();
                if (to == null || !to.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                    throw new BusinessException("INVALID_EMAIL", "Informe um e-mail válido do destinatário.");
                }
                if (c.getSubject() == null) {
                    throw new BusinessException("SUBJECT_REQUIRED", "Informe o assunto do e-mail.");
                }
                c.setAddress(to);
                List<EmailService.Attachment> files = attachments(user, req.documentIds(), c);
                email.send(officeId, to, c.getSubject(), html(c.getBody()), user.email(), files);
            } else {
                String phone = req.to() != null && !req.to().isBlank() ? req.to() : client == null ? null
                        : client.getWhatsapp() != null ? client.getWhatsapp() : client.getPhone();
                if (phone == null) {
                    throw new BusinessException("INVALID_PHONE", "Cliente sem WhatsApp/telefone cadastrado.");
                }
                c.setAddress(WhatsAppService.normalize(phone));
                c.setExternalId(whatsapp.send(officeId, phone, c.getBody(), req.whatsappTemplate(), req.whatsappParams()));
            }
            c.setStatus(Communication.Status.ENVIADO);
        } catch (BusinessException e) {
            if (List.of("EMAIL_FAILED", "WHATSAPP_FAILED").contains(e.getCode())) {
                c.setStatus(Communication.Status.FALHOU);
                c.setError(e.getMessage());
                communications.save(officeId, c);
            }
            throw e;
        }
        communications.save(officeId, c);
        audit.record(user, "COMMUNICATION_SENT", "communication", c.getId(), (req.channel() == Communication.Channel.EMAIL ? "E-mail" : "WhatsApp")
                + " enviado" + (c.getClientName() == null ? "" : " para " + c.getClientName()) + (c.getSubject() == null ? "" : ": " + c.getSubject()));
        return c;
    }

    /** Registro manual (ligação, atendimento presencial, carta, mensagem recebida fora do sistema). */
    public Communication register(AuthenticatedUser user, RegisterRequest req) {
        if (req.channel() == null || req.body() == null || req.body().isBlank()) {
            throw new BusinessException("INVALID_REGISTER", "Informe o canal e o resumo do contato.");
        }
        Communication c = base(user, req.channel(), req.direction() == null ? Communication.Direction.SAIDA : req.direction(), req.clientId(), req.caseId());
        c.setStatus(Communication.Status.REGISTRADO);
        c.setSubject(req.subject() == null || req.subject().isBlank() ? null : req.subject().trim());
        c.setBody(req.body().trim().length() > 8000 ? req.body().trim().substring(0, 8000) : req.body().trim());
        c.setFollowUpDate(req.followUpDate());
        communications.save(user.officeId(), c);
        return c;
    }

    public Communication completeFollowUp(AuthenticatedUser user, String id) {
        Communication c = communications.findById(user.requireOffice(), id).orElseThrow(() -> new NotFoundException("Comunicação"));
        c.setFollowUpDone(true);
        c.setUpdatedAt(Instant.now());
        c.setUpdatedBy(user.uid());
        communications.save(user.officeId(), c);
        return c;
    }

    /** Convite por e-mail aos convidados externos do compromisso, com o arquivo .ics anexado. */
    public int inviteGuests(AuthenticatedUser user, String appointmentId) {
        Appointment a = calendar.load(user, appointmentId);
        List<AppointmentGuest> guests = a.getGuests().stream().filter(g -> g.getEmail() != null && !g.getEmail().isBlank()).toList();
        if (guests.isEmpty()) {
            throw new BusinessException("NO_GUESTS", "Nenhum convidado com e-mail neste compromisso.");
        }
        byte[] ics = calendar.ics(user, appointmentId).getBytes(StandardCharsets.UTF_8);
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm").withZone(CourtCalendarService.ZONE);
        int sent = 0;
        for (AppointmentGuest g : guests) {
            String body = "Olá, " + g.getName() + ".\n\nVocê foi convidado(a) para: " + a.getTitle() + "\nData: " + f.format(a.getStart())
                    + (a.getLocation() == null ? "" : "\nLocal: " + a.getLocation())
                    + (a.getMeetingUrl() == null ? "" : "\nLink da reunião: " + a.getMeetingUrl())
                    + "\n\nO convite segue anexo (arquivo .ics) para adicionar à sua agenda.\n\n" + user.displayName();
            email.send(user.officeId(), g.getEmail(), "Convite: " + a.getTitle(), html(body), user.email(),
                    List.of(new EmailService.Attachment("convite.ics", ics, "text/calendar; charset=utf-8; method=PUBLISH")));
            Communication c = base(user, Communication.Channel.EMAIL, Communication.Direction.SAIDA, a.getClientId(), a.getCaseId());
            c.setAddress(g.getEmail());
            c.setSubject("Convite: " + a.getTitle());
            c.setBody(body);
            c.setAttachments(List.of("convite.ics"));
            c.setStatus(Communication.Status.ENVIADO);
            communications.save(user.officeId(), c);
            sent++;
        }
        audit.record(user, "APPOINTMENT_INVITES_SENT", "appointment", appointmentId, sent + " convite(s) enviado(s) por e-mail: " + a.getTitle());
        return sent;
    }

    /** Lembretes de retorno do dia (job diário): notifica quem registrou o contato. */
    public int notifyFollowUps(String officeId, LocalDate today) {
        int n = 0;
        for (Communication c : communications.list(communications.collection(officeId).whereEqualTo("followUpDate", today.toString()), 500)) {
            if (c.isFollowUpDone() || c.isFollowUpNotified() || c.getCreatedBy() == null) {
                continue;
            }
            notifications.notify(officeId, c.getCreatedBy(), "FOLLOW_UP", "Retornar ao cliente hoje",
                    (c.getClientName() == null ? "Contato" : c.getClientName()) + (c.getSubject() == null ? "" : " — " + c.getSubject()),
                    c.getClientId() == null ? "/notificacoes" : "/clientes/" + c.getClientId());
            c.setFollowUpNotified(true);
            communications.save(officeId, c);
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ recebimento (WhatsApp)

    /** Mensagem recebida pelo webhook: registra no histórico e vincula ao cliente pelo número. */
    public void receiveWhatsApp(String officeId, WhatsAppService.Incoming in) {
        if (communications.list(communications.collection(officeId).whereEqualTo("externalId", in.messageId()), 1).size() > 0) {
            return;
        }
        Client client = clients.list(clients.collection(officeId), 5000).stream()
                .filter(cl -> sameNumber(cl.getWhatsapp(), in.from()) || sameNumber(cl.getPhone(), in.from())).findFirst().orElse(null);
        Communication c = new Communication();
        c.setId(UUID.randomUUID().toString());
        c.setChannel(Communication.Channel.WHATSAPP);
        c.setDirection(Communication.Direction.ENTRADA);
        c.setStatus(Communication.Status.RECEBIDO);
        c.setAddress(in.from());
        c.setBody(in.text().length() > 8000 ? in.text().substring(0, 8000) : in.text());
        c.setExternalId(in.messageId());
        c.setCreatedByName(in.name() == null ? in.from() : in.name());
        c.setCreatedAt(in.timestamp() > 0 ? Instant.ofEpochSecond(in.timestamp()) : Instant.now());
        if (client != null) {
            c.setClientId(client.getId());
            c.setClientName(client.getName());
            if (client.getResponsibleUid() != null) {
                notifications.notify(officeId, client.getResponsibleUid(), "WHATSAPP", "Mensagem de " + client.getName() + " no WhatsApp",
                        c.getBody().length() > 120 ? c.getBody().substring(0, 120) + "…" : c.getBody(), "/clientes/" + client.getId());
            }
        }
        communications.save(officeId, c);
    }

    private static boolean sameNumber(String a, String from) {
        if (a == null || from == null) {
            return false;
        }
        String d = a.replaceAll("\\D", "");
        return d.length() >= 10 && from.endsWith(d.substring(d.length() - 8)) && from.length() >= 12;
    }

    // ------------------------------------------------------------------ modelos

    public List<MessageTemplate> templates(AuthenticatedUser user) {
        return templates.list(templates.collection(user.requireOffice()).orderBy("name"), 200);
    }

    public MessageTemplate saveTemplate(AuthenticatedUser user, String id, TemplateRequest req) {
        String officeId = user.requireOffice();
        if (req.name() == null || req.name().isBlank() || req.body() == null || req.body().isBlank() || req.channel() == null) {
            throw new BusinessException("INVALID_TEMPLATE", "Informe nome, canal e texto do modelo.");
        }
        MessageTemplate t = id == null ? new MessageTemplate() : templates.findById(officeId, id).orElseThrow(() -> new NotFoundException("Modelo"));
        t.setName(req.name().trim());
        t.setChannel(req.channel());
        t.setSubject(req.subject() == null || req.subject().isBlank() ? null : req.subject().trim());
        t.setBody(req.body().trim().length() > 8000 ? req.body().trim().substring(0, 8000) : req.body().trim());
        t.setWhatsappTemplate(req.whatsappTemplate() == null || req.whatsappTemplate().isBlank() ? null : req.whatsappTemplate().trim());
        Instant now = Instant.now();
        if (id == null) {
            t.setId(UUID.randomUUID().toString());
            t.setCreatedAt(now);
            t.setCreatedBy(user.uid());
            t.setCreatedByName(user.displayName());
        }
        t.setUpdatedAt(now);
        t.setUpdatedBy(user.uid());
        templates.save(officeId, t);
        return t;
    }

    public void deleteTemplate(AuthenticatedUser user, String id) {
        templates.findById(user.requireOffice(), id).orElseThrow(() -> new NotFoundException("Modelo"));
        templates.delete(user.officeId(), id);
    }

    /** Preenche os campos do modelo com os dados do cliente/processo/escritório/advogado (texto puro). */
    public Rendered render(AuthenticatedUser user, String templateId, String clientId, String caseId) {
        MessageTemplate t = templates.findById(user.requireOffice(), templateId).orElseThrow(() -> new NotFoundException("Modelo"));
        Map<String, String> values = editor.values(user, clientId, caseId, null);
        List<String> missing = new ArrayList<>();
        return new Rendered(fill(t.getSubject(), values, missing), fill(t.getBody(), values, missing), missing.stream().distinct().toList());
    }

    private static String fill(String text, Map<String, String> values, List<String> missing) {
        if (text == null) {
            return null;
        }
        Matcher m = FIELD.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1));
            if (v == null || v.isBlank()) {
                missing.add(m.group(1));
                v = "[" + m.group(1) + "]";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers

    private Communication base(AuthenticatedUser user, Communication.Channel channel, Communication.Direction direction, String clientId, String caseId) {
        Communication c = new Communication();
        c.setId(UUID.randomUUID().toString());
        c.setChannel(channel);
        c.setDirection(direction);
        if (caseId != null && !caseId.isBlank()) {
            LegalCase lc = cases.load(user, caseId);
            c.setCaseId(lc.getId());
            c.setCaseNumber(lc.getCnjNumber());
            c.setClientId(lc.getClientId());
            c.setClientName(lc.getClientName());
            c.setRestricted(lc.isRestricted());
        } else if (clientId != null && !clientId.isBlank()) {
            Client cl = clientService.load(user, clientId);
            c.setClientId(cl.getId());
            c.setClientName(cl.getName());
        }
        c.setCreatedAt(Instant.now());
        c.setCreatedBy(user.uid());
        c.setCreatedByName(user.displayName());
        return c;
    }

    private List<EmailService.Attachment> attachments(AuthenticatedUser user, List<String> ids, Communication c) {
        List<EmailService.Attachment> out = new ArrayList<>();
        if (ids == null) {
            return out;
        }
        long total = 0;
        for (String id : ids.stream().distinct().limit(10).toList()) {
            var file = documents.content(user, id, null, true);
            total += file.bytes().length;
            if (total > MAX_ATTACHMENTS_BYTES) {
                throw new BusinessException("ATTACHMENTS_TOO_LARGE", "Os anexos passam de 20 MB.");
            }
            out.add(new EmailService.Attachment(file.fileName(), file.bytes(), file.contentType()));
            c.getAttachments().add(file.fileName());
        }
        return out;
    }

    static String html(String text) {
        StringBuilder sb = new StringBuilder("<div style=\"font-family:Arial,Helvetica,sans-serif;font-size:14px;line-height:1.55;color:#1f2937\">");
        for (String line : text.split("\\r?\\n", -1)) {
            sb.append(line.isBlank() ? "<br>" : "<div>" + linkify(HtmlDocument.escape(line)) + "</div>");
        }
        return sb.append("</div>").toString();
    }

    private static String linkify(String escaped) {
        return escaped.replaceAll("(https://[^\\s<]+)", "<a href=\"$1\">$1</a>");
    }
}
