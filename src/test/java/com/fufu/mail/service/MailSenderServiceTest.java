package com.fufu.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fufu.mail.MailChannelStore;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class MailSenderServiceTest {

    private final MailSenderService senderService = new MailSenderService(null, null, null);

    private static MailChannelStore.ResolvedSender sender(String encryption, int port) {
        return new MailChannelStore.ResolvedSender("custom", true, null, "smtp.example.com", port,
            "user@example.com", "secret", "from@example.com", "fufu 的博客", encryption);
    }

    @Test
    void buildsAnSslSenderForPort465() {
        var mailSender = senderService.buildSender(sender("SSL", 465));
        Properties properties = mailSender.getJavaMailProperties();

        assertThat(mailSender.getHost()).isEqualTo("smtp.example.com");
        assertThat(mailSender.getPort()).isEqualTo(465);
        assertThat(mailSender.getUsername()).isEqualTo("user@example.com");
        assertThat(properties.getProperty("mail.smtp.ssl.enable")).isEqualTo("true");
        assertThat(properties.getProperty("mail.smtp.auth")).isEqualTo("true");
        assertThat(properties.getProperty("mail.smtp.connectiontimeout")).isNotBlank();
    }

    @Test
    void buildsAStartTlsSenderForPort587() {
        var properties = senderService.buildSender(sender("TLS", 587)).getJavaMailProperties();
        assertThat(properties.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
        assertThat(properties.get("mail.smtp.ssl.enable")).isNull();
    }

    @Test
    void buildsAPlainSenderForNone() {
        var properties = senderService.buildSender(sender("NONE", 25)).getJavaMailProperties();
        assertThat(properties.getProperty("mail.smtp.ssl.enable")).isEqualTo("false");
        assertThat(properties.getProperty("mail.smtp.starttls.enable")).isEqualTo("false");
    }

    @Test
    void fallsBackToPort465WhenNoPortIsUsable() {
        assertThat(senderService.buildSender(sender("SSL", 0)).getPort()).isEqualTo(465);
    }

    @Test
    void reportsTheDeepestCauseAsTheFailureMessage() {
        var error = new RuntimeException("wrapper", new IllegalStateException("连接被拒绝"));
        assertThat(MailSenderService.readableMessage(error)).isEqualTo("连接被拒绝");
    }

    @Test
    void neverReturnsAnEmptyFailureMessage() {
        assertThat(MailSenderService.readableMessage(new RuntimeException()))
            .isEqualTo("RuntimeException");
    }
}