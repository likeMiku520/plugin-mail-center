package com.fufu.mail;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Everything needed to send mail. {@code source} decides where the SMTP account comes
 * from: {@code halo} reuses Halo's own notifier configuration, {@code custom} uses the
 * fields stored here. The record is persisted as JSON in a Secret, never in a ConfigMap.
 */
public record MailChannel(
    String source,
    String notifierDescriptor,
    String host,
    Integer port,
    String username,
    String password,
    String sender,
    String displayName,
    String encryption,
    Boolean apiEnabled,
    String apiKey,
    Integer rateLimitPerMinute) {

    public static final String SOURCE_HALO = "halo";
    public static final String SOURCE_CUSTOM = "custom";

    public static final String DEFAULT_NOTIFIER_DESCRIPTOR = "default-email-notifier";

    public static MailChannel empty() {
        return new MailChannel(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public String sourceOrDefault() {
        return SOURCE_CUSTOM.equalsIgnoreCase(source) ? SOURCE_CUSTOM : SOURCE_HALO;
    }

    public boolean usesHaloConfig() {
        return SOURCE_HALO.equals(sourceOrDefault());
    }

    public String notifierDescriptorOrDefault() {
        return notifierDescriptor == null || notifierDescriptor.isBlank()
            ? DEFAULT_NOTIFIER_DESCRIPTOR
            : notifierDescriptor.trim();
    }

    public boolean apiEnabledOrDefault() {
        return apiEnabled != null && apiEnabled;
    }

    public String encryptionOrDefault() {
        if (encryption == null || encryption.isBlank()) {
            return "SSL";
        }
        var value = encryption.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (value) {
            case "TLS", "NONE", "SSL" -> value;
            default -> "SSL";
        };
    }

    public int portOrDefault() {
        return port == null || port <= 0 ? 465 : port;
    }

    public String senderOrDefault() {
        if (sender != null && !sender.isBlank()) {
            return sender.trim();
        }
        return username == null ? "" : username.trim();
    }

    public String displayNameOrDefault() {
        if (displayName != null && !displayName.isBlank()) {
            return displayName.trim();
        }
        if (username != null && !username.isBlank()) {
            return username.trim();
        }
        return senderOrDefault();
    }

    /** Generates a fresh trigger key; the caller decides when to replace the stored one. */
    public static String newApiKey() {
        var bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return "mc_" + HexFormat.of().formatHex(bytes);
    }

    public MailChannel withApiKey(String key) {
        return new MailChannel(source, notifierDescriptor, host, port, username, password, sender,
            displayName, encryption, apiEnabled, key, rateLimitPerMinute);
    }
}