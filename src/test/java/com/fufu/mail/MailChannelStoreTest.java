package com.fufu.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fufu.mail.dto.MailDto;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MailChannelStoreTest {

    private final MailChannelStore store = new MailChannelStore(null);

    @Test
    void readsHaloSenderConfiguration() {
        var sender = store.parseHaloSender("""
            {"sender": {"enable": true, "displayName": "fufu 的博客", "username": "bot@example.com",
             "password": "token", "host": "smtp.example.com", "port": 465,
             "encryption": "SSL", "sender": "noreply@example.com"}}
            """);

        assertThat(sender).isNotNull();
        assertThat(sender.enabled()).isTrue();
        assertThat(sender.host()).isEqualTo("smtp.example.com");
        assertThat(sender.port()).isEqualTo(465);
        assertThat(sender.username()).isEqualTo("bot@example.com");
        assertThat(sender.password()).isEqualTo("token");
        assertThat(sender.sender()).isEqualTo("noreply@example.com");
    }

    @Test
    void reportsADisabledHaloNotifier() {
        var sender = store.parseHaloSender(
            "{\"sender\": {\"enable\": false, \"host\": \"smtp.example.com\"}}");
        assertThat(sender).isNotNull();
        assertThat(sender.enabled()).isFalse();
        assertThat(sender.host()).isEqualTo("smtp.example.com");
    }

    @Test
    void returnsNullWhenTheSenderSectionIsMissing() {
        assertThat(store.parseHaloSender("{\"receiver\": {}}")).isNull();
        assertThat(store.parseHaloSender("{\"sender\": null}")).isNull();
        assertThat(store.parseHaloSender("not json at all")).isNull();
    }

    @Test
    void resolvesCustomConfigurationWithSensibleDefaults() {
        var channel = new MailChannel(MailChannel.SOURCE_CUSTOM, null, "smtp.example.com", null,
            "user@example.com", "secret", null, null, null, true, "mc_key", 30);

        assertThat(channel.usesHaloConfig()).isFalse();
        assertThat(channel.portOrDefault()).isEqualTo(465);
        assertThat(channel.encryptionOrDefault()).isEqualTo("SSL");
        assertThat(channel.senderOrDefault()).isEqualTo("user@example.com");
        assertThat(channel.displayNameOrDefault()).isEqualTo("user@example.com");
    }

    @Test
    void normalisesTheEncryptionValue() {
        var channel = new MailChannel(null, null, null, null, null, null, null, null, "tls",
            null, null, null);
        assertThat(channel.encryptionOrDefault()).isEqualTo("TLS");
        assertThat(channel.sourceOrDefault()).isEqualTo(MailChannel.SOURCE_HALO);
        assertThat(channel.apiEnabledOrDefault()).isFalse();
        assertThat(channel.notifierDescriptorOrDefault())
            .isEqualTo(MailChannel.DEFAULT_NOTIFIER_DESCRIPTOR);
    }

    @Test
    void generatesADistinctApiKeyEveryTime() {
        var first = MailChannel.newApiKey();
        var second = MailChannel.newApiKey();

        assertThat(first).startsWith("mc_").hasSize(51);
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void survivesAJsonRoundTripThroughTheSecretFormat() throws Exception {
        var mapper = new ObjectMapper();
        var channel = new MailChannel(MailChannel.SOURCE_CUSTOM, "default-email-notifier",
            "smtp.example.com", 587, "user@example.com", "s3cret", "noreply@example.com",
            "fufu 的博客", "TLS", true, "mc_abc", 120);

        var restored = mapper.readValue(mapper.writeValueAsString(channel), MailChannel.class);

        assertThat(restored).isEqualTo(channel);
        assertThat(restored.password()).isEqualTo("s3cret");
        assertThat(restored.encryptionOrDefault()).isEqualTo("TLS");
    }

    @Test
    void readsASendPayloadFromAPlainMap() {
        var mapper = new ObjectMapper();
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("templateType", "CUSTOM");
        body.put("templateName", "mail-template-welcome-a1b2c3");
        body.put("targets", java.util.List.of(Map.of("email", "a@example.com", "name", "小明")));
        body.put("variables", Map.of("code", "123456"));
        body.put("subjectOverride", null);

        var payload = mapper.convertValue(body, MailDto.SendPayload.class);

        assertThat(payload.templateType()).isEqualTo("CUSTOM");
        assertThat(payload.targets()).hasSize(1);
        assertThat(payload.targets().get(0).email()).isEqualTo("a@example.com");
        assertThat(payload.variables()).containsEntry("code", "123456");
    }
}