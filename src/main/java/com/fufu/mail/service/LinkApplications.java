package com.fufu.mail.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fufu.mail.dto.MailDto;
import java.util.Map;
import run.halo.app.extension.Extension;

/**
 * Reads the links plugin's {@code LinkApplication} extension without linking against it.
 * The plugin is optional, so its classes are never referenced here; everything is read
 * from the serialized form.
 */
public final class LinkApplications {

    public static final String API_VERSION = "core.halo.run/v1alpha1";
    public static final String KIND = "LinkApplication";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVING = "APPROVING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LinkApplications() {
    }

    /** Cheap check that decides whether a watcher event is worth parsing. */
    public static boolean isLinkApplication(Extension extension) {
        return extension != null
            && API_VERSION.equals(extension.getApiVersion())
            && KIND.equals(extension.getKind());
    }

    public static MailDto.LinkApplicationView toView(Extension extension) {
        if (!isLinkApplication(extension)) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = MAPPER.convertValue(extension, Map.class);
            return toView(data);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static MailDto.LinkApplicationView toView(Map<String, Object> data) {
        if (data == null) {
            return null;
        }
        if (data.get("apiVersion") != null && !API_VERSION.equals(data.get("apiVersion"))) {
            return null;
        }
        if (data.get("kind") != null && !KIND.equals(data.get("kind"))) {
            return null;
        }
        Map<String, Object> metadata = map(data.get("metadata"));
        Map<String, Object> spec = map(data.get("spec"));
        Map<String, Object> origin = map(spec.get("origin"));
        Map<String, Object> comment = map(origin.get("comment"));
        return new MailDto.LinkApplicationView(
            text(metadata.get("name")),
            text(spec.get("status")),
            text(spec.get("displayName")),
            text(spec.get("url")),
            text(spec.get("logo")),
            text(spec.get("description")),
            text(spec.get("email")),
            text(spec.get("backlink")),
            text(origin.get("type")),
            text(comment.get("name")));
    }

    public static boolean isDecided(String status) {
        return STATUS_APPROVED.equals(status) || STATUS_REJECTED.equals(status);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}