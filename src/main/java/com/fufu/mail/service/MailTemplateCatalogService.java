package com.fufu.mail.service;

import com.fufu.mail.dto.MailDto;
import com.fufu.mail.model.MailTemplate;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.notification.NotificationTemplate;
import run.halo.app.core.extension.notification.ReasonType;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;

/**
 * The template catalogue: every notification template Halo can send, grouped by the
 * reason that triggers it, plus the templates authored inside this plugin.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailTemplateCatalogService {

    public static final String HIDDEN_LABEL = "halo.run/hidden";
    static final String DEFAULT_LANGUAGE = "default";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ExtensionClient client;

    /** All reason types that have at least one template, in a stable display order. */
    public List<MailDto.BuiltinGroup> listBuiltinGroups() {
        var reasonTypes = client.listAll(ReasonType.class, new ListOptions(), ExtensionUtil.defaultSort());
        var templates = client.listAll(NotificationTemplate.class, new ListOptions(),
            ExtensionUtil.defaultSort());

        Map<String, List<NotificationTemplate>> byReason = new LinkedHashMap<>();
        for (var template : templates) {
            var reason = reasonOf(template);
            if (reason != null) {
                byReason.computeIfAbsent(reason, key -> new ArrayList<>()).add(template);
            }
        }

        var groups = new ArrayList<MailDto.BuiltinGroup>();
        for (var reasonType : reasonTypes) {
            var name = reasonType.getMetadata().getName();
            var spec = reasonType.getSpec();
            groups.add(new MailDto.BuiltinGroup(
                name,
                spec == null || spec.getDisplayName() == null ? name : spec.getDisplayName(),
                spec == null ? null : spec.getDescription(),
                isHidden(reasonType),
                variablesOf(reasonType),
                byReason.getOrDefault(name, List.of()).stream()
                    .map(this::toBuiltin)
                    .sorted(Comparator.comparing(MailDto.BuiltinTemplate::language))
                    .toList()));
        }
        groups.sort(Comparator
            .comparing((MailDto.BuiltinGroup group) -> group.templates().isEmpty())
            .thenComparing(MailDto.BuiltinGroup::hidden)
            .thenComparing(MailDto.BuiltinGroup::displayName));
        return groups;
    }

    public Optional<NotificationTemplate> findBuiltin(String name) {
        return client.fetch(NotificationTemplate.class, name);
    }

    /**
     * Picks the template Halo would actually use for a reason: the default language
     * wins, otherwise the newest one.
     */
    public Optional<NotificationTemplate> effectiveBuiltin(String reasonType) {
        return client.listAll(NotificationTemplate.class, new ListOptions(), ExtensionUtil.defaultSort())
            .stream()
            .filter(template -> reasonType.equals(reasonOf(template)))
            .min(Comparator
                .comparing((NotificationTemplate template) -> !DEFAULT_LANGUAGE.equals(languageOf(template)))
                .thenComparing(template -> template.getMetadata().getName()));
    }

    public Optional<ReasonType> findReasonType(String name) {
        return client.fetch(ReasonType.class, name);
    }

    public List<MailDto.CustomTemplate> listCustom() {
        return client.listAll(MailTemplate.class, new ListOptions(), ExtensionUtil.defaultSort())
            .stream()
            .map(this::toCustom)
            .toList();
    }

    /** Public projection so callers can return a freshly written template to the console. */
    public MailDto.CustomTemplate toCustomView(MailTemplate template) {
        return toCustom(template);
    }

    public Optional<MailTemplate> findCustom(String name) {
        return client.fetch(MailTemplate.class, name);
    }

    public MailTemplate createCustom(MailDto.CustomTemplatePayload payload) {
        var template = new MailTemplate();
        var metadata = new Metadata();
        metadata.setName(uniqueName(payload.name() == null || payload.name().isBlank()
            ? payload.displayName() : payload.name()));
        template.setMetadata(metadata);
        template.setSpec(new MailTemplate.Spec());
        apply(template.getSpec(), payload);
        client.create(template);
        return template;
    }

    public MailTemplate updateCustom(String name, MailDto.CustomTemplatePayload payload) {
        var template = client.fetch(MailTemplate.class, name)
            .orElseThrow(() -> new IllegalArgumentException("自定义模板不存在：" + name));
        if (template.getSpec() == null) {
            template.setSpec(new MailTemplate.Spec());
        }
        apply(template.getSpec(), payload);
        client.update(template);
        return template;
    }

    public void deleteCustom(String name) {
        client.fetch(MailTemplate.class, name).ifPresent(client::delete);
    }

    /** Names are generated here rather than by the server so the caller gets one back. */
    static String uniqueName(String source) {
        var slug = slugify(source);
        var bytes = new byte[3];
        RANDOM.nextBytes(bytes);
        return "mail-template-" + slug + "-" + HexFormat.of().formatHex(bytes);
    }

    static String slugify(String source) {
        if (source == null) {
            return "custom";
        }
        var builder = new StringBuilder();
        for (var character : source.toLowerCase(Locale.ROOT).toCharArray()) {
            if (character >= 'a' && character <= 'z' || character >= '0' && character <= '9') {
                builder.append(character);
            } else if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '-') {
                builder.append('-');
            }
        }
        while (builder.length() > 0 && builder.charAt(builder.length() - 1) == '-') {
            builder.setLength(builder.length() - 1);
        }
        if (builder.length() > 32) {
            builder.setLength(32);
        }
        return builder.length() == 0 ? "custom" : builder.toString();
    }

    private void apply(MailTemplate.Spec spec, MailDto.CustomTemplatePayload payload) {
        if (payload.displayName() == null || payload.displayName().isBlank()) {
            throw new IllegalArgumentException("模板名称不能为空");
        }
        if (payload.subject() == null || payload.subject().isBlank()) {
            throw new IllegalArgumentException("邮件标题不能为空");
        }
        if ((payload.content() == null || payload.content().isBlank())
            && (payload.rawContent() == null || payload.rawContent().isBlank())) {
            throw new IllegalArgumentException("HTML 正文和纯文本正文至少要填一个");
        }
        spec.setDisplayName(payload.displayName().trim());
        spec.setDescription(payload.description());
        spec.setSubject(payload.subject());
        spec.setContent(payload.content());
        spec.setRawContent(payload.rawContent());
        spec.setEnabled(payload.enabled() == null || payload.enabled());
        spec.setVariables(payload.variables() == null ? List.of() : payload.variables().stream()
            .filter(variable -> variable.name() != null && !variable.name().isBlank())
            .map(variable -> {
                var target = new MailTemplate.Variable();
                target.setName(variable.name().trim());
                target.setLabel(variable.label());
                target.setType(variable.type() == null ? "string" : variable.type());
                target.setSampleValue(variable.sampleValue());
                target.setRequired(variable.required());
                return target;
            })
            .toList());
    }

    private MailDto.BuiltinTemplate toBuiltin(NotificationTemplate template) {
        var spec = template.getSpec();
        var content = spec == null ? null : spec.getTemplate();
        return new MailDto.BuiltinTemplate(
            template.getMetadata().getName(),
            reasonOf(template),
            languageOf(template),
            content == null ? null : content.getTitle(),
            content == null ? null : content.getHtmlBody(),
            content == null ? null : content.getRawBody(),
            template.getMetadata().getCreationTimestamp() == null
                ? null : template.getMetadata().getCreationTimestamp().toString());
    }

    private MailDto.CustomTemplate toCustom(MailTemplate template) {
        var spec = template.getSpec();
        var variables = new ArrayList<MailDto.TemplateVariable>();
        if (spec != null && spec.getVariables() != null) {
            for (var variable : spec.getVariables()) {
                variables.add(new MailDto.TemplateVariable(variable.getName(),
                    variable.getLabel() == null ? variable.getName() : variable.getLabel(),
                    variable.getType() == null ? "string" : variable.getType(),
                    variable.getSampleValue(),
                    variable.getRequired() != null && variable.getRequired()));
            }
        }
        return new MailDto.CustomTemplate(
            template.getMetadata().getName(),
            spec == null ? null : spec.getDisplayName(),
            spec == null ? null : spec.getDescription(),
            spec == null ? null : spec.getSubject(),
            spec == null ? null : spec.getContent(),
            spec == null ? null : spec.getRawContent(),
            variables,
            spec == null || spec.getEnabled() == null || spec.getEnabled(),
            template.getMetadata().getCreationTimestamp() == null
                ? null : template.getMetadata().getCreationTimestamp().toString());
    }

    /** Attributes a reason hands to its template, used to build the variable form. */
    public List<MailDto.TemplateVariable> variablesOf(ReasonType reasonType) {
        var spec = reasonType.getSpec();
        if (spec == null || spec.getProperties() == null) {
            return List.of();
        }
        return spec.getProperties().stream()
            .map(property -> new MailDto.TemplateVariable(property.getName(),
                property.getDescription() == null || property.getDescription().isBlank()
                    ? property.getName() : property.getDescription(),
                property.getType() == null ? "string" : property.getType(),
                sampleValue(property.getType()),
                !property.isOptional()))
            .toList();
    }

    public List<MailDto.TemplateVariable> variablesOf(MailTemplate template) {
        return toCustom(template).variables();
    }

    static boolean isHidden(ReasonType reasonType) {
        var labels = reasonType.getMetadata().getLabels();
        return labels != null && "true".equalsIgnoreCase(labels.get(HIDDEN_LABEL));
    }

    static String reasonOf(NotificationTemplate template) {
        var spec = template.getSpec();
        if (spec == null || spec.getReasonSelector() == null) {
            return null;
        }
        return spec.getReasonSelector().getReasonType();
    }

    static String languageOf(NotificationTemplate template) {
        var spec = template.getSpec();
        if (spec == null || spec.getReasonSelector() == null) {
            return DEFAULT_LANGUAGE;
        }
        var language = spec.getReasonSelector().getLanguage();
        return language == null || language.isBlank() ? DEFAULT_LANGUAGE : language;
    }

    /** Sample value for a declared attribute, so a preview never renders nulls. */
    static String sampleValue(String type) {
        return switch (type == null ? "string" : type.toLowerCase(Locale.ROOT)) {
            case "number", "integer", "int", "long", "float", "double" -> "123";
            case "boolean", "bool" -> "true";
            case "date", "datetime", "instant" -> "2026-01-01T00:00:00Z";
            case "url" -> "https://blog.fufu520.cn";
            case "email" -> "visitor@example.com";
            case "array", "list" -> "示例";
            default -> "示例内容";
        };
    }
}