package com.fufu.mail.service;

import com.fufu.mail.dto.MailDto;
import com.fufu.mail.model.MailSendLog;
import com.fufu.mail.model.MailTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.notification.NotificationTemplate;
import run.halo.app.core.extension.notification.ReasonType;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.Metadata;

/**
 * Turns "template + recipients + variables" into delivered mail: resolves the template,
 * renders it per recipient, hands it to the sender and writes a record. Every path in
 * the plugin — manual sending, the trigger API and automatic rules — goes through here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailDispatchService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$");
    private static final int PREVIEW_LENGTH = 300;

    /** A single stored body never exceeds this, so one huge mail cannot bloat the store. */
    private static final int STORED_BODY_LIMIT = 120_000;

    private final ExtensionClient client;
    private final MailTemplateCatalogService catalog;
    private final MailRenderService renderService;
    private final MailSenderService senderService;

    /** A template resolved into the four strings a send needs. */
    public record ResolvedTemplate(String type, String name, String displayName, String subject,
                                   String html, String raw,
                                   List<MailDto.TemplateVariable> variables) {
    }

    public ResolvedTemplate resolve(String templateType, String templateName) {
        if (templateName == null || templateName.isBlank()) {
            throw new IllegalArgumentException("请选择邮件模板");
        }
        if ("CUSTOM".equalsIgnoreCase(templateType)) {
            var template = catalog.findCustom(templateName)
                .orElseThrow(() -> new IllegalArgumentException("自定义模板不存在：" + templateName));
            var spec = template.getSpec();
            return new ResolvedTemplate("CUSTOM", templateName,
                spec == null ? templateName : spec.getDisplayName(),
                spec == null ? null : spec.getSubject(),
                spec == null ? null : spec.getContent(),
                spec == null ? null : spec.getRawContent(),
                catalog.variablesOf(template));
        }
        var template = catalog.findBuiltin(templateName)
            .orElseThrow(() -> new IllegalArgumentException("站内邮件模板不存在：" + templateName));
        var content = template.getSpec() == null ? null : template.getSpec().getTemplate();
        var reasonType = MailTemplateCatalogService.reasonOf(template);
        var display = reasonType;
        var variables = List.<MailDto.TemplateVariable>of();
        if (reasonType != null) {
            Optional<ReasonType> found = catalog.findReasonType(reasonType);
            if (found.isPresent()) {
                var spec = found.get().getSpec();
                if (spec != null && spec.getDisplayName() != null && !spec.getDisplayName().isBlank()) {
                    display = spec.getDisplayName();
                }
                variables = catalog.variablesOf(found.get());
            }
        }
        return new ResolvedTemplate("BUILTIN", templateName,
            display == null ? templateName : display,
            content == null ? null : content.getTitle(),
            content == null ? null : content.getHtmlBody(),
            content == null ? null : content.getRawBody(),
            variables);
    }

    /** Variables filled with their declared samples, so a preview is never full of blanks. */
    public Map<String, Object> defaults(ResolvedTemplate template) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (var variable : template.variables()) {
            values.put(variable.name(), variable.sampleValue() == null ? "" : variable.sampleValue());
        }
        return values;
    }

    public MailDto.TemplatePreview preview(String templateType, String templateName,
                                           Map<String, Object> variables) {
        var template = resolve(templateType, templateName);
        var merged = defaults(template);
        if (variables != null) {
            variables.forEach((key, value) -> {
                if (key != null && !key.isBlank()) {
                    merged.put(key, value);
                }
            });
        }
        var model = renderService.sampleModel(null, merged);
        var rendered = renderService.renderAll(template.subject(), template.html(), template.raw(), model);
        return new MailDto.TemplatePreview(rendered.subject(), rendered.html(), rendered.raw(),
            template.variables());
    }

    /** Validates an unsaved template by rendering it against sample values. */
    public void validate(String subject, String html, String raw,
                         List<MailDto.TemplateVariable> variables) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (variables != null) {
            for (var variable : variables) {
                if (variable.name() != null && !variable.name().isBlank()) {
                    values.put(variable.name(), variable.sampleValue() == null ? "" : variable.sampleValue());
                }
            }
        }
        var model = renderService.sampleModel(null, values);
        renderService.renderAll(subject, html, raw, model);
    }

    public MailDto.SendSummary send(MailDto.SendPayload payload, String source, String triggerName) {
        var template = resolve(payload.templateType(), payload.templateName());
        var targets = payload.targets() == null ? List.<MailDto.SendTarget>of() : payload.targets();
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个收件人");
        }
        var results = new ArrayList<MailDto.SendResult>();
        for (var target : targets) {
            var email = normalizeEmail(target.email());
            if (email == null) {
                results.add(new MailDto.SendResult(target.email(), target.name(), false,
                    "邮箱地址不合法", null));
                continue;
            }
            results.add(deliver(template, email, target.name(), payload.variables(),
                payload.subjectOverride(), source, triggerName, null));
        }
        var success = (int) results.stream().filter(MailDto.SendResult::success).count();
        return new MailDto.SendSummary(results.size(), success, results.size() - success, results);
    }

    /**
     * Renders and delivers one mail, then records the attempt. A {@code dedupKey} makes
     * the record name deterministic, which is how an automatic rule avoids sending the
     * same mail twice across restarts.
     */
    public MailDto.SendResult deliver(ResolvedTemplate template, String email, String recipientName,
                                      Map<String, Object> variables, String subjectOverride,
                                      String source, String triggerName, String dedupKey) {
        var logName = dedupKey == null ? null : deterministicName(dedupKey);
        if (logName != null && client.fetch(MailSendLog.class, logName).isPresent()) {
            return new MailDto.SendResult(email, recipientName, true, null, logName);
        }

        var merged = defaults(template);
        if (variables != null) {
            variables.forEach((key, value) -> {
                if (key != null && !key.isBlank()) {
                    merged.put(key, value);
                }
            });
        }
        var model = renderService.baseModel(email, recipientName, merged);
        MailRenderService.Rendered rendered;
        try {
            var subject = subjectOverride == null || subjectOverride.isBlank()
                ? template.subject() : subjectOverride;
            rendered = renderService.renderAll(subject, template.html(), template.raw(), model);
        } catch (MailRenderService.RenderException e) {
            writeLog(logName, email, recipientName, template, subjectOverride, "FAILED",
                e.getMessage(), source, triggerName, dedupKey, 0L, null, null);
            return new MailDto.SendResult(email, recipientName, false, e.getMessage(), logName);
        }

        var outcome = senderService.send(email, rendered.subject(), rendered.html(), rendered.raw());
        writeLog(logName, email, recipientName, template, rendered.subject(),
            outcome.success() ? "SUCCESS" : "FAILED", outcome.error(), source, triggerName,
            dedupKey, outcome.durationMs(), rendered.raw(), rendered.html());
        return new MailDto.SendResult(email, recipientName, outcome.success(), outcome.error(), logName);
    }

    /** Outcome recorded for something that produced no mail of our own. */
    public static final String STATUS_SKIPPED = "SKIPPED";

    /** Outcome recorded for mail the site itself sent, where delivery cannot be observed. */
    public static final String STATUS_SENT_BY_SITE = "SENT";

    /**
     * Records an outcome that produced no mail: a rule that matched but had nobody to send
     * to, a template that was deleted, or a notification the site sent on its own. Without
     * these rows the log looks empty exactly when something is wrong.
     */
    public String recordNote(String email, String recipientName, String subject, String status,
                             String reason, String source, String templateType, String templateName,
                             String templateDisplayName, String triggerName, String dedupKey,
                             Instant sentAt) {
        return recordNote(email, recipientName, subject, status, reason, source, templateType,
            templateName, templateDisplayName, triggerName, dedupKey, sentAt, null, null);
    }

    /** Same as above, for records that also carry the body the site sent. */
    public String recordNote(String email, String recipientName, String subject, String status,
                             String reason, String source, String templateType, String templateName,
                             String templateDisplayName, String triggerName, String dedupKey,
                             Instant sentAt, String htmlBody, String rawBody) {
        var logName = dedupKey == null ? null : deterministicName(dedupKey);
        if (logName != null && client.fetch(MailSendLog.class, logName).isPresent()) {
            return logName;
        }
        try {
            var record = new MailSendLog();
            var metadata = new Metadata();
            if (logName == null) {
                metadata.setGenerateName(MailSendLog.DETERMINISTIC_PREFIX);
            } else {
                metadata.setName(logName);
            }
            record.setMetadata(metadata);
            var spec = new MailSendLog.Spec();
            spec.setRecipient(email == null || email.isBlank() ? "（无地址）" : email);
            spec.setRecipientName(recipientName);
            spec.setTemplateType(templateType);
            spec.setTemplateName(templateName);
            spec.setTemplateDisplayName(templateDisplayName);
            spec.setSubject(subject);
            spec.setStatus(status);
            spec.setError(reason);
            spec.setSource(source);
            spec.setTriggerName(triggerName);
            spec.setDedupKey(dedupKey);
            spec.setSentAt(sentAt == null ? Instant.now() : sentAt);
            spec.setHtmlBody(cap(htmlBody));
            spec.setRawBody(cap(rawBody));
            record.setSpec(spec);
            client.create(record);
            return record.getMetadata().getName();
        } catch (Exception e) {
            log.warn("Failed to write a mail note for [{}]", email, e);
            return null;
        }
    }

    public static String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        var value = email.trim();
        return EMAIL.matcher(value).matches() ? value : null;
    }

    static String deterministicName(String dedupKey) {
        try {
            var digest = MessageDigest.getInstance("SHA-1")
                .digest(dedupKey.getBytes(StandardCharsets.UTF_8));
            return MailSendLog.DETERMINISTIC_PREFIX
                + HexFormat.of().formatHex(digest).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void writeLog(String logName, String email, String recipientName,
                          ResolvedTemplate template, String subject, String status, String error,
                          String source, String triggerName, String dedupKey, long durationMs,
                          String raw, String html) {
        try {
            var record = new MailSendLog();
            var metadata = new Metadata();
            if (logName == null) {
                metadata.setGenerateName(MailSendLog.DETERMINISTIC_PREFIX);
            } else {
                metadata.setName(logName);
            }
            record.setMetadata(metadata);
            var spec = new MailSendLog.Spec();
            spec.setRecipient(email);
            spec.setRecipientName(recipientName);
            spec.setTemplateType(template.type());
            spec.setTemplateName(template.name());
            spec.setTemplateDisplayName(template.displayName());
            spec.setSubject(subject);
            spec.setStatus(status);
            spec.setError(error);
            spec.setSource(source);
            spec.setTriggerName(triggerName);
            spec.setDedupKey(dedupKey);
            spec.setSentAt(Instant.now());
            spec.setDurationMs(durationMs);
            spec.setPreview(preview(raw));
            spec.setRawBody(cap(raw));
            spec.setHtmlBody(cap(html));
            record.setSpec(spec);
            client.create(record);
        } catch (Exception e) {
            log.warn("Failed to write a mail record for [{}]", email, e);
        }
    }

    /** Keeps one record from swallowing the whole store when a body is unusually large. */
    static String cap(String body) {
        if (body == null || body.length() <= STORED_BODY_LIMIT) {
            return body;
        }
        return body.substring(0, STORED_BODY_LIMIT) + "\n<!-- 内容过长，仅保留前一部分 -->";
    }

    private static String preview(String raw) {
        if (raw == null) {
            return null;
        }
        var compact = raw.replaceAll("\\s+", " ").trim();
        return compact.length() > PREVIEW_LENGTH ? compact.substring(0, PREVIEW_LENGTH) + "…" : compact;
    }

    /** Template display names for lists that only carry a reference. */
    public String displayNameOf(String templateType, String templateName) {
        try {
            return resolve(templateType, templateName).displayName();
        } catch (RuntimeException e) {
            return templateName;
        }
    }

    /** Subject of a stored template, used to show a hint in the trigger list. */
    public String subjectOf(String templateType, String templateName) {
        try {
            return resolve(templateType, templateName).subject();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** True when the referenced template still exists. */
    public boolean templateExists(String templateType, String templateName) {
        if (templateName == null || templateName.isBlank()) {
            return false;
        }
        if ("CUSTOM".equalsIgnoreCase(templateType)) {
            return catalog.findCustom(templateName).isPresent();
        }
        return catalog.findBuiltin(templateName).isPresent();
    }

    /** All reasons a template list can be built from, kept for the console filter. */
    public List<String> customTemplateNames() {
        return client.listAll(MailTemplate.class, new run.halo.app.extension.ListOptions(),
                run.halo.app.extension.ExtensionUtil.defaultSort())
            .stream()
            .map(template -> template.getMetadata().getName())
            .toList();
    }

    static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}