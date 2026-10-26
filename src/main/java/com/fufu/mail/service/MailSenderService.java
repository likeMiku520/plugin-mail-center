package com.fufu.mail.service;

import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import com.fufu.mail.MailChannelStore;

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
        var html = """
            <div style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;\
            max-width:520px;margin:0 auto;padding:24px;border-radius:16px;\
            background:#f6fffe;border:1px solid #b8ece7;color:#0f3f3c;">
              <h2 style="margin:0 0 12px;font-size:18px;color:#1f9d94;">邮件中心连接正常</h2>
              <p style="margin:0;line-height:1.7;font-size:14px;">
                如果你看到这封邮件，说明发信配置已经可用，可以回到后台开始发送了。
              </p>
            </div>
            """;
        var raw = "邮件中心连接正常。如果你看到这封邮件，说明发信配置已经可用。";
        return send(to, "【邮件中心】连接测试", html, raw);
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