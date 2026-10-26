package com.fufu.mail.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.exceptions.TemplateEngineException;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.StringTemplateResolver;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.infra.ExternalUrlSupplier;

/**
 * Renders mail templates with the same Thymeleaf engine Halo uses, so the syntax of a
 * Halo notification template and one written here is identical. Rendering is also the
 * validator: a template that fails to process is reported before anything is sent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailRenderService {

    /** Guards against a runaway template being used as a payload. */
    public static final int MAX_PART_LENGTH = 512 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TemplateEngine TEMPLATE_ENGINE = createEngine();

    private final ExtensionClient client;
    private final ExternalUrlSupplier externalUrlSupplier;

    /** Raised when a template cannot be processed; the message is shown to the operator. */
    public static class RenderException extends RuntimeException {
        public RenderException(String message) {
            super(message);
        }

        public RenderException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Renders one template part, converting any engine failure into a readable message. */
    public String render(String partName, String template, Map<String, Object> model) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        if (template.length() > MAX_PART_LENGTH) {
            throw new RenderException(partName + "内容过长，最多 " + MAX_PART_LENGTH + " 个字符");
        }
        try {
            return TEMPLATE_ENGINE.process(template, new Context(Locale.getDefault(), model));
        } catch (TemplateEngineException e) {
            throw new RenderException(partName + "模板语法错误：" + compact(e), e);
        }
    }

    /** Renders every non-blank part and reports the first failure. */
    public Rendered renderAll(String subject, String html, String raw, Map<String, Object> model) {
        var renderedSubject = render("标题", subject, model).trim();
        var renderedHtml = render("HTML 正文", html, model);
        var renderedRaw = render("纯文本正文", raw, model);
        if (renderedRaw.isBlank() && !renderedHtml.isBlank()) {
            renderedRaw = toPlainText(renderedHtml);
        }
        return new Rendered(renderedSubject, renderedHtml, renderedRaw);
    }

    /** Site level values shared by every template, matching Halo's own model names. */
    public Map<String, Object> siteModel() {
        var site = new LinkedHashMap<String, Object>();
        var basic = readSiteBasic();
        site.put("title", basic.getOrDefault("title", "Halo"));
        site.put("subtitle", basic.getOrDefault("subtitle", ""));
        site.put("logo", basic.getOrDefault("logo", ""));
        site.put("url", siteUrl());
        return site;
    }

    /**
     * Base model handed to every template: the site, the recipient and an unsubscribe
     * placeholder, plus whatever variables the caller supplies.
     */
    public Map<String, Object> baseModel(String recipientEmail, String recipientName,
                                         Map<String, Object> variables) {
        Map<String, Object> model = new HashMap<>();
        var subscriber = new LinkedHashMap<String, Object>();
        subscriber.put("displayName", recipientName == null || recipientName.isBlank()
            ? recipientEmail : recipientName);
        subscriber.put("email", recipientEmail);
        subscriber.put("id", "anonymousUser#" + recipientEmail);
        model.put("subscriber", subscriber);
        model.put("site", siteModel());
        model.put("unsubscribeUrl", siteUrl() + "/uc/notifications");
        if (variables != null) {
            variables.forEach((key, value) -> {
                if (key != null && !key.isBlank()) {
                    model.put(key, value);
                }
            });
        }
        return model;
    }

    /** Values used when the operator only wants to look at a template. */
    public Map<String, Object> sampleModel(String recipientEmail, Map<String, Object> variables) {
        return baseModel(recipientEmail == null ? "visitor@example.com" : recipientEmail,
            "示例访客", variables);
    }

    public String siteUrl() {
        try {
            var uri = externalUrlSupplier.get();
            if (uri != null && uri.toString().length() > 1) {
                var value = uri.toString();
                return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
            }
        } catch (Exception e) {
            log.debug("External url is unavailable", e);
        }
        return "";
    }

    Map<String, String> readSiteBasic() {
        var values = new HashMap<String, String>();
        try {
            var configMap = client.fetch(ConfigMap.class, "system").orElse(null);
            if (configMap == null || configMap.getData() == null) {
                return values;
            }
            var basic = configMap.getData().get("basic");
            if (basic == null || basic.isBlank()) {
                return values;
            }
            var node = MAPPER.readTree(basic);
            node.fields().forEachRemaining(entry -> {
                if (entry.getValue().isValueNode()) {
                    values.put(entry.getKey(), entry.getValue().asText());
                }
            });
        } catch (Exception e) {
            log.debug("Site basic settings are unreadable", e);
        }
        return values;
    }

    /** Very small HTML to text conversion, only used when no plain text part was written. */
    public static String toPlainText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        var text = html
            .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "")
            .replaceAll("(?i)<br\\s*/?>", "\n")
            .replaceAll("(?i)</(p|div|tr|h[1-6]|li)>", "\n")
            .replaceAll("(?s)<[^>]+>", "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"");
        return text.replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
    }

    /** Depth first search for the deepest message that carries a line number. */
    private static String compact(TemplateEngineException error) {
        Throwable current = error;
        String message = null;
        while (current != null) {
            if (current instanceof TemplateEngineException) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        var text = message == null ? error.getMessage() : message;
        if (text == null) {
            return "无法解析模板";
        }
        text = text.replace('\n', ' ').replace('\r', ' ');
        var marker = text.indexOf(" (template:");
        if (marker >= 0) {
            text = text.substring(0, marker);
        }
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }

    private static TemplateEngine createEngine() {
        var resolver = new StringTemplateResolver();
        resolver.setCacheable(false);
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    /** A fully rendered mail, ready to be handed to the SMTP client. */
    public record Rendered(String subject, String html, String raw) {
    }
}