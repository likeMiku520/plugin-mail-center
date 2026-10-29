package com.fufu.mail.service;

import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Reads the notification templates Halo ships with, so an edited system template can be
 * restored to what Halo originally delivered. The file lives in the application jar and
 * is visible through the plugin class loader.
 */
@Slf4j
@Component
public class NotificationTemplateDefaults {

    static final String RESOURCE = "extensions/notification-templates.yaml";

    private final Map<String, Map<String, String>> cache = new LinkedHashMap<>();
    private volatile boolean loaded;

    /** Original title, HTML body and plain text body of a bundled template. */
    public record Defaults(String title, String htmlBody, String rawBody) {
    }

    public Defaults find(String name) {
        ensureLoaded();
        var entry = cache.get(name);
        if (entry == null) {
            return null;
        }
        return new Defaults(entry.get("title"), entry.get("htmlBody"), entry.get("rawBody"));
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (cache) {
            if (loaded) {
                return;
            }
            try (InputStream input = openResource()) {
                if (input == null) {
                    log.warn("Bundled notification templates were not found on the class path");
                } else {
                    cache.putAll(parse(input));
                }
            } catch (Exception e) {
                log.warn("Bundled notification templates could not be read", e);
            }
            loaded = true;
        }
    }

    /** Pulls the template bodies out of Halo's resource file. */
    static Map<String, Map<String, String>> parse(InputStream input) {
        Map<String, Map<String, String>> parsed = new LinkedHashMap<>();
        var yaml = new Yaml();
        for (Object document : yaml.loadAll(input)) {
            if (!(document instanceof Map<?, ?> map)) {
                continue;
            }
            if (!"NotificationTemplate".equals(map.get("kind"))) {
                continue;
            }
            var name = nameOf(map);
            var template = templateOf(map);
            if (name != null && template != null) {
                parsed.put(name, template);
            }
        }
        log.debug("Parsed {} bundled notification templates", parsed.size());
        return parsed;
    }

    private InputStream openResource() {
        var loader = Thread.currentThread().getContextClassLoader();
        var input = loader == null ? null : loader.getResourceAsStream(RESOURCE);
        if (input != null) {
            return input;
        }
        return NotificationTemplateDefaults.class.getClassLoader().getResourceAsStream(RESOURCE);
    }

    private static String nameOf(Map<?, ?> map) {
        if (map.get("metadata") instanceof Map<?, ?> metadata) {
            var name = metadata.get("name");
            return name == null ? null : name.toString();
        }
        return null;
    }

    private static Map<String, String> templateOf(Map<?, ?> map) {
        if (!(map.get("spec") instanceof Map<?, ?> spec)) {
            return null;
        }
        if (!(spec.get("template") instanceof Map<?, ?> template)) {
            return null;
        }
        Map<String, String> values = new HashMap<>();
        for (var field : new String[] {"title", "htmlBody", "rawBody"}) {
            var value = template.get(field);
            if (value != null) {
                values.put(field, value.toString());
            }
        }
        return values;
    }
}