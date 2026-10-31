package com.fufu.mail;

import java.util.HashMap;
import java.util.Map;
import run.halo.app.extension.AbstractExtension;

/**
 * Labels this plugin writes onto extensions it manages. Hiding is expressed as a label
 * rather than by removing the record, so Halo keeps sending its own notifications while
 * the template disappears from this plugin's pickers.
 */
public final class MailCenterLabels {

    /** Marks a template as hidden inside this plugin. */
    public static final String HIDDEN = "mail.fufu520.cn/hidden";

    /** Marks the templates and rules this plugin created for the links plugin. */
    public static final String AUTO_PROVISIONED = "mail.fufu520.cn/auto-provisioned";

    /** Marks a template the plugin ships itself; the value is its stable key. */
    public static final String SYSTEM = "mail.fufu520.cn/system";

    private MailCenterLabels() {
    }

    public static boolean isHidden(AbstractExtension extension) {
        return "true".equalsIgnoreCase(label(extension, HIDDEN));
    }

    public static String systemKey(AbstractExtension extension) {
        return label(extension, SYSTEM);
    }

    public static boolean isSystem(AbstractExtension extension) {
        return systemKey(extension) != null;
    }

    public static void markSystem(AbstractExtension extension, String key) {
        put(extension, SYSTEM, key);
    }

    public static boolean isAutoProvisioned(AbstractExtension extension) {
        return "true".equalsIgnoreCase(label(extension, AUTO_PROVISIONED));
    }

    public static void setHidden(AbstractExtension extension, boolean hidden) {
        put(extension, HIDDEN, hidden ? "true" : null);
    }

    public static void markAutoProvisioned(AbstractExtension extension) {
        put(extension, AUTO_PROVISIONED, "true");
    }

    private static String label(AbstractExtension extension, String key) {
        if (extension.getMetadata() == null || extension.getMetadata().getLabels() == null) {
            return null;
        }
        return extension.getMetadata().getLabels().get(key);
    }

    private static void put(AbstractExtension extension, String key, String value) {
        if (extension.getMetadata() == null) {
            return;
        }
        var labels = extension.getMetadata().getLabels();
        if (labels == null) {
            if (value == null) {
                return;
            }
            labels = new HashMap<>();
            extension.getMetadata().setLabels(labels);
        }
        if (value == null) {
            labels.remove(key);
        } else {
            labels.put(key, value);
        }
    }

    static Map<String, String> orEmpty(Map<String, String> labels) {
        return labels == null ? Map.of() : labels;
    }
}