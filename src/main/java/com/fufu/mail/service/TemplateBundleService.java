package com.fufu.mail.service;

import com.fufu.mail.dto.MailDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.notification.NotificationTemplate;

/**
 * Turns every template into one JSON document and reads such a document back in.
 *
 * <p>This is the file an operator exports, hands to an editor, and imports again. Because
 * the editor is often a general purpose tool rather than this console, reading is
 * deliberately forgiving: field names may use either the export spelling or the internal
 * one, unknown keys are ignored, a missing field keeps the stored value, and one broken
 * entry never stops the rest. Nothing is ever created or deleted, only existing templates
 * are rewritten, so a half edited file cannot wipe a site.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TemplateBundleService {

    public static final String FORMAT = "mail-center-templates";
    public static final int FORMAT_VERSION = 1;

    private static final String SECTION_BUILTIN = "builtin";
    private static final String SECTION_CUSTOM = "custom";
    private static final String SECTION_SYSTEM = "system";

    private final MailTemplateCatalogService catalog;
    private final SystemTemplates systemTemplates;
    private final MailRenderService renderService;

    // ------------------------------------------------------------------ export

    public MailDto.TemplateBundle export() {
        var site = renderService.siteModel();
        return new MailDto.TemplateBundle(
            FORMAT,
            FORMAT_VERSION,
            Instant.now().toString(),
            asText(site.get("title")),
            asText(site.get("url")),
            notes(),
            new MailDto.BundleGlobals(globalVariables(), globalHint()),
            exportBuiltin(),
            exportCustom(),
            exportSystem());
    }

    private List<MailDto.BundleBuiltin> exportBuiltin() {
        var items = new ArrayList<MailDto.BundleBuiltin>();
        for (var group : catalog.listBuiltinGroups()) {
            for (var template : group.templates()) {
                items.add(new MailDto.BundleBuiltin(
                    template.name(),
                    group.reasonType(),
                    group.displayName(),
                    template.language(),
                    template.title(),
                    template.htmlBody(),
                    template.rawBody(),
                    group.variables()));
            }
        }
        return items;
    }

    private List<MailDto.BundleCustom> exportCustom() {
        return catalog.listCustom().stream()
            .map(template -> new MailDto.BundleCustom(
                template.name(),
                template.displayName(),
                template.description(),
                template.subject(),
                template.content(),
                template.rawContent(),
                template.enabled(),
                template.hidden(),
                template.variables()))
            .toList();
    }

    private List<MailDto.BundleSystem> exportSystem() {
        return systemTemplates.list().stream()
            .map(template -> new MailDto.BundleSystem(
                template.key(),
                template.name(),
                template.displayName(),
                template.description(),
                template.subject(),
                template.htmlBody(),
                template.rawBody(),
                template.variables()))
            .toList();
    }

    private static List<String> notes() {
        return List.of(
            "这份文件由「邮件中心」导出，可以直接改内容，再原样导回去。",
            "只改 title（站内模板标题）、subject（邮件标题）、htmlBody（HTML 正文）和 rawBody（纯文本正文）就够了。",
            "name 决定改的是哪一个模板，不要改它，也不要删掉整段，否则这一条会被跳过。",
            "变量写成 [(${变量名})]，比如 [(${commenter})]。每个模板能用的变量写在自己的 variables 里，"
                + "所有模板都能用的写在 globals 里。",
            "builtin 是 Halo 自带的通知模板，custom 是本插件里的模板，system 是插件自带的模板（测试邮件）。",
            "导入只修改同名的已有模板，不会新建也不会删除；某个字段留空表示保持原样。");
    }

    private static List<MailDto.TemplateVariable> globalVariables() {
        return List.of(
            new MailDto.TemplateVariable("subscriber.displayName", "收件人显示名", "string", "示例访客", true),
            new MailDto.TemplateVariable("subscriber.email", "收件人邮箱", "string", "visitor@example.com", true),
            new MailDto.TemplateVariable("site.title", "站点名称", "string", "我的博客", true),
            new MailDto.TemplateVariable("site.subtitle", "站点副标题", "string", "", false),
            new MailDto.TemplateVariable("site.url", "站点地址", "string", "https://example.com", true),
            new MailDto.TemplateVariable("site.logo", "站点图标", "string", "", false),
            new MailDto.TemplateVariable("unsubscribeUrl", "通知中心地址", "string",
                "https://example.com/uc/notifications", false));
    }

    private static String globalHint() {
        return "这七个值由站点提供，不需要在模板里声明，写 [(${site.title})] 这样就能用。";
    }

    // ------------------------------------------------------------------ import

    public MailDto.BundleImportResult importBundle(Map<String, Object> document, boolean dryRun) {
        if (document == null || document.isEmpty()) {
            throw new IllegalArgumentException("文件里没有内容");
        }
        var format = asText(document.get("format"));
        if (format != null && !format.startsWith(FORMAT)) {
            throw new IllegalArgumentException("这不是「邮件中心」导出的模板文件");
        }
        var items = new ArrayList<MailDto.BundleImportItem>();
        applyBuiltin(asList(document.get(SECTION_BUILTIN)), dryRun, items);
        applyCustom(asList(document.get(SECTION_CUSTOM)), dryRun, items);
        applySystem(asList(document.get(SECTION_SYSTEM)), dryRun, items);

        int updated = count(items, "updated");
        int unchanged = count(items, "unchanged");
        int skipped = count(items, "skipped");
        int failed = count(items, "failed");
        if (!dryRun && updated > 0) {
            log.info("Imported a template bundle: {} updated, {} unchanged, {} skipped, {} failed",
                updated, unchanged, skipped, failed);
        }
        return new MailDto.BundleImportResult(dryRun, items.size(), updated, unchanged, skipped,
            failed, items);
    }

    private void applyBuiltin(List<Map<String, Object>> entries, boolean dryRun,
                              List<MailDto.BundleImportItem> items) {
        for (var entry : entries) {
            var name = firstText(entry, "name", "templateName");
            var label = firstText(entry, "title", "displayName");
            try {
                if (name == null) {
                    items.add(item(SECTION_BUILTIN, null, label, "skipped", "缺少 name 字段，无法确定改哪一个模板"));
                    continue;
                }
                Optional<NotificationTemplate> found = catalog.findBuiltin(name);
                if (found.isEmpty()) {
                    items.add(item(SECTION_BUILTIN, name, label, "skipped",
                        "Halo 里没有这个通知模板，可能已经被删掉了"));
                    continue;
                }
                var template = found.get();
                var content = template.getSpec() == null ? null : template.getSpec().getTemplate();
                var currentTitle = content == null ? null : content.getTitle();
                var currentHtml = content == null ? null : content.getHtmlBody();
                var currentRaw = content == null ? null : content.getRawBody();

                var title = coalesce(firstText(entry, "title", "subject"), currentTitle);
                var html = coalesce(firstText(entry, "htmlBody", "content", "html"), currentHtml);
                var raw = coalesce(firstText(entry, "rawBody", "rawContent", "raw"), currentRaw);
                var reasonType = firstText(entry, "reasonType");
                var displayName = reasonDisplayName(reasonType);

                if (title == null || title.isBlank()) {
                    items.add(item(SECTION_BUILTIN, name, displayName, "failed", "标题不能为空"));
                    continue;
                }
                if (isBlank(html) && isBlank(raw)) {
                    items.add(item(SECTION_BUILTIN, name, displayName, "failed",
                        "HTML 正文和纯文本正文至少要有一个"));
                    continue;
                }
                if (Objects.equals(title, currentTitle) && Objects.equals(html, currentHtml)
                    && Objects.equals(raw, currentRaw)) {
                    items.add(item(SECTION_BUILTIN, name, displayName, "unchanged", "内容没有变化"));
                    continue;
                }
                if (!dryRun) {
                    catalog.updateBuiltin(name, new MailDto.BuiltinTemplatePayload(title, html, raw));
                }
                items.add(item(SECTION_BUILTIN, name, displayName, "updated",
                    dryRun ? "将更新标题与正文" : "标题与正文已更新"));
            } catch (Exception e) {
                items.add(item(SECTION_BUILTIN, name, label, "failed", messageOf(e)));
            }
        }
    }

    private void applyCustom(List<Map<String, Object>> entries, boolean dryRun,
                             List<MailDto.BundleImportItem> items) {
        var existing = catalog.listCustom();
        for (var entry : entries) {
            var name = firstText(entry, "name", "templateName");
            var displayName = firstText(entry, "displayName", "title");
            try {
                MailDto.CustomTemplate target = null;
                if (name != null) {
                    target = existing.stream().filter(item -> name.equals(item.name()))
                        .findFirst().orElse(null);
                }
                if (target == null && displayName != null) {
                    var wanted = displayName.trim().toLowerCase(Locale.ROOT);
                    target = existing.stream()
                        .filter(item -> item.displayName() != null
                            && item.displayName().trim().toLowerCase(Locale.ROOT).equals(wanted))
                        .findFirst().orElse(null);
                }
                if (target == null) {
                    items.add(item(SECTION_CUSTOM, name, displayName, "skipped",
                        "找不到同名模板。导入只修改已经存在的模板，不会新建"));
                    continue;
                }
                var resolvedName = target.name();
                var nextDisplayName = coalesce(firstText(entry, "displayName", "title"),
                    target.displayName());
                var nextSubject = coalesce(firstText(entry, "subject", "title"), target.subject());
                var nextHtml = coalesce(firstText(entry, "htmlBody", "content", "html"),
                    target.content());
                var nextRaw = coalesce(firstText(entry, "rawBody", "rawContent", "raw"),
                    target.rawContent());
                var nextDescription = coalesce(firstText(entry, "description"), target.description());
                var nextEnabled = firstBoolean(entry, "enabled") != null
                    ? firstBoolean(entry, "enabled") : target.enabled();
                var nextVariables = entry.containsKey("variables")
                    ? parseVariables(entry.get("variables")) : target.variables();

                if (nextDisplayName == null || nextDisplayName.isBlank()) {
                    items.add(item(SECTION_CUSTOM, resolvedName, target.displayName(), "failed",
                        "模板名称不能为空"));
                    continue;
                }
                if (nextSubject == null || nextSubject.isBlank()) {
                    items.add(item(SECTION_CUSTOM, resolvedName, target.displayName(), "failed",
                        "邮件标题不能为空"));
                    continue;
                }
                if (isBlank(nextHtml) && isBlank(nextRaw)) {
                    items.add(item(SECTION_CUSTOM, resolvedName, target.displayName(), "failed",
                        "HTML 正文和纯文本正文至少要有一个"));
                    continue;
                }
                var changed = !Objects.equals(nextDisplayName, target.displayName())
                    || !Objects.equals(nextSubject, target.subject())
                    || !Objects.equals(nextHtml, target.content())
                    || !Objects.equals(nextRaw, target.rawContent())
                    || !Objects.equals(nextDescription, target.description())
                    || nextEnabled != target.enabled()
                    || !sameVariables(nextVariables, target.variables());
                if (!changed) {
                    items.add(item(SECTION_CUSTOM, resolvedName, target.displayName(), "unchanged",
                        "内容没有变化"));
                    continue;
                }
                if (!dryRun) {
                    catalog.updateCustom(resolvedName, new MailDto.CustomTemplatePayload(
                        resolvedName, nextDisplayName, nextDescription, nextSubject, nextHtml,
                        nextRaw, nextVariables, nextEnabled));
                }
                items.add(item(SECTION_CUSTOM, resolvedName, nextDisplayName, "updated",
                    dryRun ? "将更新模板内容" : "模板已更新"));
            } catch (Exception e) {
                items.add(item(SECTION_CUSTOM, name, displayName, "failed", messageOf(e)));
            }
        }
    }

    private void applySystem(List<Map<String, Object>> entries, boolean dryRun,
                             List<MailDto.BundleImportItem> items) {
        var known = systemTemplates.byKey();
        for (var entry : entries) {
            var key = firstText(entry, "key", "name");
            try {
                var normalised = normaliseSystemKey(key);
                var target = normalised == null ? null : known.get(normalised);
                if (target == null) {
                    items.add(item(SECTION_SYSTEM, key, null, "skipped", "插件里没有这个系统模板"));
                    continue;
                }
                var nextSubject = coalesce(firstText(entry, "subject", "title"), target.subject());
                var nextHtml = coalesce(firstText(entry, "htmlBody", "content", "html"),
                    target.htmlBody());
                var nextRaw = coalesce(firstText(entry, "rawBody", "rawContent", "raw"),
                    target.rawBody());
                if (nextSubject == null || nextSubject.isBlank()) {
                    items.add(item(SECTION_SYSTEM, normalised, target.displayName(), "failed",
                        "邮件标题不能为空"));
                    continue;
                }
                if (isBlank(nextHtml) && isBlank(nextRaw)) {
                    items.add(item(SECTION_SYSTEM, normalised, target.displayName(), "failed",
                        "HTML 正文和纯文本正文至少要有一个"));
                    continue;
                }
                if (Objects.equals(nextSubject, target.subject())
                    && Objects.equals(nextHtml, target.htmlBody())
                    && Objects.equals(nextRaw, target.rawBody())) {
                    items.add(item(SECTION_SYSTEM, normalised, target.displayName(), "unchanged",
                        "内容没有变化"));
                    continue;
                }
                if (!dryRun) {
                    systemTemplates.update(normalised,
                        new MailDto.SystemTemplatePayload(nextSubject, nextHtml, nextRaw));
                }
                items.add(item(SECTION_SYSTEM, normalised, target.displayName(), "updated",
                    dryRun ? "将更新模板内容" : "模板已更新"));
            } catch (Exception e) {
                items.add(item(SECTION_SYSTEM, key, null, "failed", messageOf(e)));
            }
        }
    }

    /** Accepts either the key ("test") or the record name ("mail-template-system-test"). */
    private static String normaliseSystemKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var trimmed = value.trim();
        if (trimmed.startsWith(MailTemplateCatalogService.SYSTEM_PREFIX)) {
            return trimmed.substring(MailTemplateCatalogService.SYSTEM_PREFIX.length());
        }
        return trimmed;
    }

    // ------------------------------------------------------------------ helpers

    private String reasonDisplayName(String reasonType) {
        if (reasonType == null) {
            return null;
        }
        return catalog.findReasonType(reasonType)
            .map(reasonType_ -> reasonType_.getSpec())
            .map(spec -> spec.getDisplayName() == null ? reasonType : spec.getDisplayName())
            .orElse(reasonType);
    }

    static List<MailDto.TemplateVariable> parseVariables(Object raw) {
        var list = new ArrayList<MailDto.TemplateVariable>();
        for (var item : asList(raw)) {
            var name = firstText(item, "name", "key");
            if (name == null || name.isBlank()) {
                continue;
            }
            var required = firstBoolean(item, "required");
            list.add(new MailDto.TemplateVariable(
                name.trim(),
                coalesce(firstText(item, "label", "description"), name.trim()),
                coalesce(firstText(item, "type"), "string"),
                firstText(item, "sampleValue", "sample", "value"),
                required != null && required));
        }
        return list;
    }

    private static boolean sameVariables(List<MailDto.TemplateVariable> left,
                                         List<MailDto.TemplateVariable> right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left.size() != right.size()) {
            return false;
        }
        for (var index = 0; index < left.size(); index++) {
            var a = left.get(index);
            var b = right.get(index);
            if (!Objects.equals(a.name(), b.name()) || !Objects.equals(a.label(), b.label())
                || !Objects.equals(a.type(), b.type())
                || !Objects.equals(a.sampleValue(), b.sampleValue())
                || a.required() != b.required()) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object value) {
        var result = new ArrayList<Map<String, Object>>();
        if (value instanceof List<?> list) {
            for (var item : list) {
                if (item instanceof Map<?, ?> map) {
                    var normalised = new LinkedHashMap<String, Object>();
                    map.forEach((key, entry) -> {
                        if (key != null) {
                            normalised.put(String.valueOf(key), entry);
                        }
                    });
                    result.add(normalised);
                }
            }
        }
        return result;
    }

    private static String firstText(Map<String, Object> map, String... keys) {
        for (var key : keys) {
            var value = map.get(key);
            if (value != null) {
                var text = String.valueOf(value);
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    private static Boolean firstBoolean(Map<String, Object> map, String key) {
        var value = map.get(key);
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text.trim())) {
                return true;
            }
            if ("false".equalsIgnoreCase(text.trim())) {
                return false;
            }
        }
        return null;
    }

    private static String coalesce(String preferred, String fallback) {
        return preferred == null ? fallback : preferred;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String messageOf(Exception e) {
        var message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static int count(List<MailDto.BundleImportItem> items, String status) {
        return (int) items.stream().filter(item -> status.equals(item.status())).count();
    }

    private static MailDto.BundleImportItem item(String section, String name, String displayName,
                                                 String status, String message) {
        return new MailDto.BundleImportItem(section, name, displayName, status, message);
    }
}