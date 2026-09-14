package com.bento.crm.common.mail;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thin wrapper over {@link JavaMailSender} for the handful of transactional messages the CRM
 * sends. Templates are plain HTML files under {@code resources/mail} with {@code {{key}}}
 * placeholders rather than a template engine: the set of messages is small, and adding
 * Thymeleaf for three files would cost a dependency and a view resolver for no gain.
 */
@Service
@Slf4j
public class EmailService {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final MailProperties properties;
    private final String smtpUsername;
    private final Map<String, String> templateCache = new ConcurrentHashMap<>();

    public EmailService(ObjectProvider<JavaMailSender> mailSender,
                        MailProperties properties,
                        @Value("${spring.mail.username:}") String smtpUsername) {
        this.mailSender = mailSender;
        this.properties = properties;
        this.smtpUsername = smtpUsername;
    }

    /**
     * Sent off the request thread: a slow or unreachable SMTP relay must not make the invite
     * request itself hang or fail. The invitation row is already committed by then, so a send
     * failure leaves a PENDING invitation the admin can resend.
     */
    @Async
    public void sendHtml(String to, String subject, String templateName, Map<String, String> variables) {
        String body = render(templateName, variables);
        sendRawHtml(to, subject, body);
    }

    /**
     * Sends an HTML email with direct HTML content (e.g. reminder text or custom campaign markup).
     */
    @Async
    public void sendRawHtml(String to, String subject, String bodyHtml) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (!properties.isEnabled() || sender == null) {
            log.warn("Mail disabled or sender not available -- skipping '{}' to {}. Body:\n{}", subject, to, bodyHtml);
            return;
        }

        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(bodyHtml, true);
            helper.setFrom(new InternetAddress(properties.getFrom(), properties.getFromName(), StandardCharsets.UTF_8.name()));
            if (properties.getReplyTo() != null && !properties.getReplyTo().isBlank()) {
                helper.setReplyTo(properties.getReplyTo());
            }
            sender.send(message);
            log.info("Successfully sent email '{}' to {}", subject, to);
        } catch (Exception e) {
            log.error("Failed to send email '{}' to {}: {}", subject, to, e.getMessage(), e);
        }
    }

    private String render(String templateName, Map<String, String> variables) {
        String template = templateCache.computeIfAbsent(templateName, this::loadTemplate);
        String rendered = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", entry.getValue() == null ? "" : entry.getValue());
        }
        return rendered;
    }

    private String loadTemplate(String templateName) {
        try (var in = new ClassPathResource("mail/" + templateName + ".html").getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Missing mail template: " + templateName, e);
        }
    }
}
