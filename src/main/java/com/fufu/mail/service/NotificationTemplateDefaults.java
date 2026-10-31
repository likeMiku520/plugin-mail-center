package com.fufu.mail.service;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Reads the notification templates Halo ships with, so an edited system template can be
 * restored to what Halo originally delivered.
 *
 * <p>The bodies live in the application jar. Their location has moved between Halo
 * releases and a site may carry templates that this jar never bundled, so the lookup is
 * deliberately forgiving: several known paths are scanned, every document is indexed by
 * both its record name and its reason type, and a template that cannot be found at all
 * falls back to the snapshot taken by {@link TemplateOriginals}.
 */
@Slf4j
@Component
public class NotificationTemplateDefaults {

    static final String[] RESOURCES = {
        "extensions/notification-templates.yaml",
        "extensions/notification-templates.yml",
    };

    static final String[] PATTERNS = {
        "classpath*:extensions/notification*.y*ml",
        "classpath*:extensions/**/notification-templates*.y*ml",
    };

    private final Map<String, Entry> byName = new LinkedHashMap<>();
    private final Map<String, Entry> byReason = new LinkedHashMap<>();
    private volatile boolean loaded;

    /** Original body of one bundled template, plus the reason it belongs to. */
    public record Entry(String reasonType, String language, String title, String htmlBody,
                        String rawBody) {
    }

    /** What a reset should restore. */
    public record Defaults(String title, String htmlBody, String rawBody) {
    }

    public Defaults find(String name) {
        return find(name, null);
    }

    /**
     * Looks the template up by record name first, then by the reason it answers. The
     * second step is what saves a site whose template record was renamed or re-created
     * under a different name by a Halo upgrade.
     */
    public Defaults find(String name, String reasonType) {
        ensureLoaded();
        var entry = name == null ? null : byName.get(name);
        if (entry == null && reasonType != null) {
            entry = byReason.get(reasonType);
        }
        if (entry == null) {
            return null;
        }
        return new Defaults(entry.title(), entry.htmlBody(), entry.rawBody());
    }

    /** Names this plugin knows how to restore, used by the console to grey out the button. */
    public boolean knows(String name, String reasonType) {
        return find(name, reasonType) != null;
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (byName) {
            if (loaded) {
                return;
            }
            var resolver = new PathMatchingResourcePatternResolver();
            var found = false;
            for (var path : RESOURCES) {
                try (InputStream input = resolver.getResource("classpath:" + path).getInputStream()) {
                    merge(parse(input));
                    found = true;
                } catch (Exception e) {
                    log.debug("Bundled notification templates are not at {}", path);
                }
            }
            for (var pattern : PATTERNS) {
                try {
                    for (Resource resource : resolver.getResources(pattern)) {
                        try (InputStream input = resource.getInputStream()) {
                            if (merge(parse(input))) {
                                found = true;
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Pattern {} could not be scanned", pattern, e);
                }
            }
            if (!found) {
                log.warn("No bundled notification templates were found on the class path; "
                    + "resetting a built in template will rely on the captured snapshot");
            }
            loaded = true;
        }
    }

    /** Keeps the first body seen for a name, so an explicit file wins over a scan. */
    private boolean merge(Map<String, Entry> parsed) {
        var added = false;
        for (var entry : parsed.entrySet()) {
            if (byName.putIfAbsent(entry.getKey(), entry.getValue()) == null) {
                added = true;
            }
            var reasonType = entry.getValue().reasonType();
            if (reasonType == null) {
                continue;
            }
            var existing = byReason.get(reasonType);
            // The default language is the one a site actually sends, so it wins.
            if (existing == null || "default".equals(entry.getValue().language())) {
                byReason.put(reasonType, entry.getValue());
            }
        }
        return added;
    }

    /** Pulls the template bodies out of Halo's resource file, keyed by record name. */
    static Map<String, Entry> parse(InputStream input) {
        Map<String, Entry> parsed = new LinkedHashMap<>();
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
                parsed.put(name, new Entry(reasonOf(map), languageOf(map), template.get("title"),
                    template.get("htmlBody"), template.get("rawBody")));
            }
        }
        return parsed;
    }

    private static String nameOf(Map<?, ?> map) {
        if (map.get("metadata") instanceof Map<?, ?> metadata) {
            var name = metadata.get("name");
            return name == null ? null : name.toString();
        }
        return null;
    }

    private static String reasonOf(Map<?, ?> map) {
        if (!(map.get("spec") instanceof Map<?, ?> spec)) {
            return null;
        }
        if (!(spec.get("reasonSelector") instanceof Map<?, ?> selector)) {
            return null;
        }
        var reasonType = selector.get("reasonType");
        return reasonType == null ? null : reasonType.toString();
    }

    private static String languageOf(Map<?, ?> map) {
        if (!(map.get("spec") instanceof Map<?, ?> spec)) {
            return "default";
        }
        if (!(spec.get("reasonSelector") instanceof Map<?, ?> selector)) {
            return "default";
        }
        var language = selector.get("language");
        return language == null ? "default" : language.toString();
    }

    private static Map<String, String> templateOf(Map<?, ?> map) {
        if (!(map.get("spec") instanceof Map<?, ?> spec)) {
            return null;
        }
        if (!(spec.get("template") instanceof Map<?, ?> template)) {
            return null;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (var field : new String[] {"title", "htmlBody", "rawBody"}) {
            var value = template.get(field);
            if (value != null) {
                values.put(field, value.toString());
            }
        }
        return values;
    }
}