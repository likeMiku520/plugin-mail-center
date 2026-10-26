package com.fufu.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.Secret;

/**
 * Reads and writes {@link MailChannel}, and resolves the SMTP account that a send
 * should use. Halo keeps its notifier credentials in a Secret rather than in the
 * notifier Setting form, so a plugin that wants to reuse them has to read the same
 * place; that is why both branches of {@link #resolve()} end in a Secret lookup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailChannelStore {

    static final String OWN_SECRET = "mail-center-secret";
    static final String OWN_KEY = "channel";
    static final String HALO_SECRET = "notifier-setting-secret";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ExtensionClient client;

    public MailChannel load() {
        return client.fetch(Secret.class, OWN_SECRET)
            .map(Secret::getStringData)
            .map(data -> data.get(OWN_KEY))
            .filter(value -> !value.isBlank())
            .map(value -> {
                try {
                    return MAPPER.readValue(value, MailChannel.class);
                } catch (Exception e) {
                    log.warn("Stored mail channel configuration is unreadable, falling back to defaults", e);
                    return MailChannel.empty();
                }
            })
            .orElseGet(MailChannel::empty);
    }

    public MailChannel save(MailChannel channel) {
        var secret = client.fetch(Secret.class, OWN_SECRET).orElseGet(() -> {
            var created = new Secret();
            created.setMetadata(new Metadata());
            created.getMetadata().setName(OWN_SECRET);
            created.setStringData(new HashMap<>());
            client.create(created);
            return created;
        });
        if (secret.getStringData() == null) {
            secret.setStringData(new HashMap<>());
        }
        try {
            secret.getStringData().put(OWN_KEY, MAPPER.writeValueAsString(channel));
        } catch (Exception e) {
            throw new IllegalStateException("无法保存发信配置：" + e.getMessage(), e);
        }
        client.update(secret);
        return channel;
    }

    public MailChannel loadOrCreateWithApiKey() {
        var channel = load();
        if (channel.apiKey() == null || channel.apiKey().isBlank()) {
            channel = save(channel.withApiKey(MailChannel.newApiKey()));
        }
        return channel;
    }

    /**
     * Resolves the SMTP account a send should use, including the reason it cannot be
     * used, so the console can show something actionable instead of a stack trace.
     */
    public ResolvedSender resolve() {
        var channel = load();
        if (channel.usesHaloConfig()) {
            var fromHalo = readHaloSender(channel.notifierDescriptorOrDefault());
            if (fromHalo == null) {
                return ResolvedSender.unusable(MailChannel.SOURCE_HALO,
                    "读不到 Halo 的邮件通知配置。请先在「设置 → 通知 → 邮件通知」里启用并填写发件设置，"
                        + "或者到本插件的「发信设置」里改成自定义 SMTP。");
            }
            if (!fromHalo.enabled) {
                return ResolvedSender.unusable(MailChannel.SOURCE_HALO,
                    "Halo 的邮件通知器当前是关闭状态。请到「设置 → 通知 → 邮件通知」打开它，"
                        + "或者到本插件的「发信设置」里改成自定义 SMTP。");
            }
            if (isBlank(fromHalo.host) || isBlank(fromHalo.username)) {
                return ResolvedSender.unusable(MailChannel.SOURCE_HALO,
                    "Halo 的邮件通知配置里缺少 SMTP 服务器或用户名，请补全后重试。");
            }
            return ResolvedSender.usable(MailChannel.SOURCE_HALO, fromHalo.host, fromHalo.port,
                fromHalo.username, fromHalo.password, fromHalo.sender, fromHalo.displayName,
                fromHalo.encryption);
        }

        if (isBlank(channel.host()) || isBlank(channel.username())) {
            return ResolvedSender.unusable(MailChannel.SOURCE_CUSTOM, "还没有填写 SMTP 服务器地址和用户名。");
        }
        return ResolvedSender.usable(MailChannel.SOURCE_CUSTOM, channel.host().trim(),
            channel.portOrDefault(), channel.username().trim(), channel.password(),
            channel.senderOrDefault(), channel.displayNameOrDefault(), channel.encryptionOrDefault());
    }

    HaloSender readHaloSender(String descriptorName) {
        return client.fetch(Secret.class, HALO_SECRET)
            .map(Secret::getStringData)
            .map(data -> data.get(descriptorName + ".json"))
            .filter(value -> !value.isBlank())
            .map(this::parseHaloSender)
            .orElse(null);
    }

    HaloSender parseHaloSender(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode sender = root.path("sender");
            if (sender.isMissingNode() || sender.isNull()) {
                return null;
            }
            return new HaloSender(
                sender.path("enable").asBoolean(false),
                text(sender, "host"),
                sender.path("port").asInt(465),
                text(sender, "username"),
                text(sender, "password"),
                text(sender, "sender"),
                text(sender, "displayName"),
                text(sender, "encryption"));
        } catch (Exception e) {
            log.warn("Halo notifier configuration is unreadable", e);
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Keeps the resolved account and its problems in one object. */
    public record ResolvedSender(String source, boolean usable, String problem, String host,
                                 int port, String username, String password, String sender,
                                 String displayName, String encryption) {

        static ResolvedSender usable(String source, String host, int port, String username,
                                     String password, String sender, String displayName, String encryption) {
            return new ResolvedSender(source, true, null, host, port, username, password, sender,
                displayName, encryption);
        }

        static ResolvedSender unusable(String source, String problem) {
            return new ResolvedSender(source, false, problem, null, 0, null, null, null, null, null);
        }

        /** Fields exposed to the console; the password is replaced by a presence flag. */
        public Set<String> secretFields() {
            return Set.of("password");
        }
    }

    /** The subset of Halo's notifier sender form this plugin cares about. */
    record HaloSender(boolean enabled, String host, int port, String username, String password,
                      String sender, String displayName, String encryption) {
    }
}