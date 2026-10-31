package com.fufu.mail.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.notification.NotificationTemplate;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.Secret;

/**
 * Keeps a copy of every notification template body the first time this plugin sees it,
 * before anything has a chance to overwrite it.
 *
 * <p>Restoring a system template normally reads Halo's bundled resource file, but that
 * file has changed location between Halo releases and a site can carry templates it never
 * bundled. The snapshot makes the reset button work regardless: it is written once from an
 * untouched record, never overwritten afterwards, and lives in this plugin's own Secret so
 * it survives Halo upgrades.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TemplateOriginals {

    static final String SECRET = "mail-center-originals";

    static final String KEY = "originals";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ExtensionClient client;

    /** Body kept for one template. Mirrors the shape used by the bundled defaults. */
    public record Body(String title, String htmlBody, String rawBody) {
    }

    /** Fills in snapshots for every template that is still untouched. Never overwrites. */
    public void capture() {
        Map<String, Body> stored;
        try {
            stored = load();
        } catch (Exception e) {
            log.debug("Original template storage is unreadable", e);
            return;
        }
        var changed = false;
        try {
            for (var template : client.listAll(NotificationTemplate.class, new ListOptions(),
                ExtensionUtil.defaultSort())) {
                var name = template.getMetadata().getName();
                if (name == null || stored.containsKey(name)
                    || ExtensionUtil.hasDoNotOverwriteLabel(template)) {
                    continue;
                }
                var content = template.getSpec() == null ? null : template.getSpec().getTemplate();
                if (content == null) {
                    continue;
                }
                stored.put(name, new Body(content.getTitle(), content.getHtmlBody(),
                    content.getRawBody()));
                changed = true;
            }
        } catch (Exception e) {
            log.debug("Notification templates are unreadable, snapshot skipped", e);
            return;
        }
        if (changed) {
            save(stored);
            log.info("Captured the original body of {} system templates", stored.size());
        }
    }

    public Body find(String name) {
        return name == null ? null : load().get(name);
    }

    /** Called after a reset, so the restored body is what a later reset restores too. */
    public void remember(String name, Body body) {
        if (name == null || body == null) {
            return;
        }
        var stored = load();
        if (stored.containsKey(name)) {
            return;
        }
        stored.put(name, body);
        save(stored);
    }

    private Map<String, Body> load() {
        var raw = client.fetch(Secret.class, SECRET)
            .map(Secret::getStringData)
            .map(data -> data.get(KEY))
            .filter(value -> !value.isBlank())
            .orElse(null);
        if (raw == null) {
            return new LinkedHashMap<>();
        }
        try {
            var type = MAPPER.getTypeFactory().constructMapType(LinkedHashMap.class, String.class,
                Body.class);
            return new LinkedHashMap<>(MAPPER.readValue(raw, type));
        } catch (Exception e) {
            log.warn("Stored template snapshots are unreadable, starting over", e);
            return new LinkedHashMap<>();
        }
    }

    private void save(Map<String, Body> stored) {
        try {
            var secret = client.fetch(Secret.class, SECRET).orElse(null);
            if (secret == null) {
                var created = new Secret();
                created.setMetadata(new Metadata());
                created.getMetadata().setName(SECRET);
                created.setStringData(new HashMap<>());
                try {
                    client.create(created);
                } catch (Exception e) {
                    log.debug("Original template storage was created by another caller", e);
                }
                secret = client.fetch(Secret.class, SECRET).orElse(null);
            }
            if (secret == null) {
                return;
            }
            if (secret.getStringData() == null) {
                secret.setStringData(new HashMap<>());
            }
            secret.getStringData().put(KEY, MAPPER.writeValueAsString(stored));
            client.update(secret);
        } catch (Exception e) {
            log.warn("Could not save the original template snapshot", e);
        }
    }
}