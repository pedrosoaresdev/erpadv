package br.com.erpadv.services.integrations;

import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.models.IntegrationProvider;
import br.com.erpadv.repositories.OfficeRepository;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/**
 * Envio de e-mail pelo SMTP do escritório (Central de Integrações) ou, na falta dele, pelo SMTP do ambiente
 * ({@code spring.mail.*} — em desenvolvimento, o Mailpit do Docker Compose).
 */
@Service
public class EmailService {

    public record Attachment(String name, byte[] bytes, String contentType) {
    }

    private final IntegrationService integrations;
    private final ObjectProvider<JavaMailSender> defaultSender;
    private final OfficeRepository offices;
    private final String defaultFrom;

    public EmailService(IntegrationService integrations, ObjectProvider<JavaMailSender> defaultSender, OfficeRepository offices,
                        @Value("${app.mail.from:}") String defaultFrom) {
        this.integrations = integrations;
        this.defaultSender = defaultSender;
        this.offices = offices;
        this.defaultFrom = defaultFrom;
    }

    public boolean available(String officeId) {
        return integrations.active(officeId, IntegrationProvider.SMTP);
    }

    /** Envia um e-mail HTML. O endereço de resposta é o do usuário que enviou. */
    public void send(String officeId, String to, String subject, String html, String replyTo, List<Attachment> attachments) {
        if (!available(officeId)) {
            throw new BusinessException("EMAIL_NOT_CONFIGURED", "Configure o e-mail (SMTP) na Central de Integrações para enviar mensagens.");
        }
        JavaMailSender sender = officeSender(officeId);
        String from = integrations.config(officeId, IntegrationProvider.SMTP, "fromAddress");
        String fromName = integrations.config(officeId, IntegrationProvider.SMTP, "fromName");
        if (sender == null) {
            sender = defaultSender.getIfAvailable();
            from = defaultFrom;
        }
        if (sender == null || from == null || from.isBlank()) {
            throw new BusinessException("EMAIL_NOT_CONFIGURED", "Remetente de e-mail não configurado.");
        }
        if (fromName == null || fromName.isBlank()) {
            fromName = offices.findById(officeId).map(o -> o.getName()).orElse("ERPAdv");
        }
        try {
            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(msg, attachments != null && !attachments.isEmpty(), StandardCharsets.UTF_8.name());
            h.setFrom(new InternetAddress(from, fromName, StandardCharsets.UTF_8.name()));
            h.setTo(to);
            if (replyTo != null && !replyTo.isBlank()) {
                h.setReplyTo(replyTo);
            }
            h.setSubject(subject);
            h.setText(html, true);
            if (attachments != null) {
                for (Attachment a : attachments) {
                    h.addAttachment(a.name(), new ByteArrayResource(a.bytes()), a.contentType());
                }
            }
            sender.send(msg);
            integrations.recordSuccess(officeId, IntegrationProvider.SMTP);
        } catch (MailException | jakarta.mail.MessagingException | java.io.UnsupportedEncodingException e) {
            String message = "Falha no envio do e-mail: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            integrations.recordError(officeId, IntegrationProvider.SMTP, message);
            throw new BusinessException("EMAIL_FAILED", message.length() > 300 ? message.substring(0, 300) : message);
        }
    }

    private JavaMailSender officeSender(String officeId) {
        String host = integrations.config(officeId, IntegrationProvider.SMTP, "host");
        if (host == null || host.isBlank()) {
            return null;
        }
        JavaMailSenderImpl s = new JavaMailSenderImpl();
        s.setHost(host);
        s.setPort(Integer.parseInt(java.util.Optional.ofNullable(integrations.config(officeId, IntegrationProvider.SMTP, "port")).orElse("587")));
        s.setUsername(integrations.config(officeId, IntegrationProvider.SMTP, "username"));
        s.setPassword(integrations.secret(officeId, IntegrationProvider.SMTP, "password"));
        s.setDefaultEncoding(StandardCharsets.UTF_8.name());
        Properties p = s.getJavaMailProperties();
        p.put("mail.smtp.auth", String.valueOf(s.getUsername() != null));
        p.put("mail.smtp.starttls.enable", String.valueOf(!"false".equalsIgnoreCase(integrations.config(officeId, IntegrationProvider.SMTP, "starttls"))));
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "20000");
        p.put("mail.smtp.writetimeout", "20000");
        if (s.getPort() == 465) {
            p.put("mail.smtp.ssl.enable", "true");
        }
        return s;
    }
}
