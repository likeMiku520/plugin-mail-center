package com.fufu.mail.service;

import com.fufu.mail.MailChannelStore;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Sends a rendered mail through the SMTP account resolved by {@link MailChannelStore}.
 * The plugin builds its own {@code JavaMailSenderImpl} rather than borrowing Halo's,
 * because only the notifier beans are shared with plugins; the mail library itself is
 * part of the plugin API, so this stays inside the supported surface.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailSenderService {

    private static final String CONNECTION_TIMEOUT_MS = "10000";
    private static final String READ_TIMEOUT_MS = "20000";
    private static final String WRITE_TIMEOUT_MS = "20000";

    private final MailChannelStore channelStore;
    private final SystemTemplates systemTemplates;
    private final MailRenderService renderService;

    /** Outcome of one delivery attempt; never throws, so a batch keeps going. */
    public record SendOutcome(boolean success, String error, long durationMs) {
    }

    public SendOutcome send(String to, String subject, String html, String raw) {
        var startedAt = System.nanoTime();
        var resolved = channelStore.resolve();
        if (!resolved.usable()) {
            return failure(resolved.problem(), startedAt);
        }
        try {
            var sender = buildSender(resolved);
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress(resolved), resolved.displayName());
            helper.setTo(to);
            helper.setSubject(subject == null ? "" : subject);
            var hasHtml = html != null && !html.isBlank();
            helper.setText(raw == null ? "" : raw, hasHtml ? html : null);
            sender.send(message);
            return new SendOutcome(true, null, elapsed(startedAt));
        } catch (Exception e) {
            log.warn("Failed to send mail to [{}]", to, e);
            return failure(readableMessage(e), startedAt);
        }
    }

    /** Confirms that the resolved account can actually deliver, used by the test button. */
    public SendOutcome sendTest(String to) {
        var template = systemTemplates.resolve(SystemTemplates.TEST_KEY);
        String subject = template.subject();
        String html = template.html();
        String raw = template.raw();
        try {
            var model = renderService.baseModel(to, null, Map.of());
            var rendered = renderService.renderAll(template.subject(), template.html(),
                template.raw(), model);
            subject = rendered.subject();
            html = rendered.html();
            raw = rendered.raw();
        } catch (RuntimeException e) {
            // A broken test template must still let the operator check the connection.
            log.warn("Test mail template could not be rendered, sending it as written", e);
        }
        return send(to, subject, html, raw);
    }

    JavaMailSenderImpl buildSender(MailChannelStore.ResolvedSender resolved) {
        var sender = new JavaMailSenderImpl();
        sender.setHost(resolved.host());
        sender.setPort(resolved.port() <= 0 ? 465 : resolved.port());
        sender.setUsername(resolved.username());
        sender.setPassword(resolved.password());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        var properties = sender.getJavaMailProperties();
        properties.put("mail.transport.protocol", "smtp");
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.connectiontimeout", CONNECTION_TIMEOUT_MS);
        properties.put("mail.smtp.timeout", READ_TIMEOUT_MS);
        properties.put("mail.smtp.writetimeout", WRITE_TIMEOUT_MS);
        switch (resolved.encryption() == null ? "SSL" : resolved.encryption()) {
            case "SSL" -> properties.put("mail.smtp.ssl.enable", "true");
            case "TLS" -> properties.put("mail.smtp.starttls.enable", "true");
            default -> {
                properties.put("mail.smtp.ssl.enable", "false");
                properties.put("mail.smtp.starttls.enable", "false");
            }
        }
        return sender;
    }

    private static String fromAddress(MailChannelStore.ResolvedSender resolved) {
        var sender = resolved.sender();
        return sender == null || sender.isBlank() ? resolved.username() : sender;
    }

    private static SendOutcome failure(String message, long startedAt) {
        return new SendOutcome(false, message, elapsed(startedAt));
    }

    private static long elapsed(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    /** Turns a mail library exception into something an operator can act on. */
    static String readableMessage(Exception e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        var text = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        if (text == null || text.isBlank()) {
            text = e.getClass().getSimpleName();
        }
        return text.length() > 400 ? text.substring(0, 400) + "…" : text;
    }
}