package com.fufu.mail.service;

import com.fufu.mail.MailCenterLabels;
import com.fufu.mail.dto.MailDto;
import com.fufu.mail.model.MailTemplate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.Metadata;

/**
 * The templates the plugin ships for itself: the mail used by the "send a test" button.
 * They are ordinary {@link MailTemplate} records carrying a system label, so they get the
 * same editor, the same bundle export and the same rendering as everything else, but they
 * cannot be deleted and never show up among the user's own templates.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemTemplates {

    public static final String TEST_KEY = "test";

    private static final String TEST_NAME = "mail-template-system-test";

    private final ExtensionClient client;

    /** What one of these templates looks like when nobody has edited it. */
    public record Defaults(String displayName, String description, String subject, String html,
                           String raw) {
    }

    public static Defaults defaultsOf(String key) {
        if (!TEST_KEY.equals(key)) {
            throw new IllegalArgumentException("未知的系统模板：" + key);
        }
        var html = """
            <div style="max-width:520px;margin:0 auto;padding:26px 24px;border:1px solid #e5e7eb;\
            border-radius:10px;background:#ffffff;font-family:-apple-system,BlinkMacSystemFont,\
            'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;color:#111827;\
            font-size:14px;line-height:1.75;">
              <div style="height:3px;width:44px;background:#39c5bb;border-radius:2px;margin-bottom:18px;"></div>
              <h2 style="margin:0 0 12px;font-size:17px;font-weight:600;color:#111827;">发信配置可用</h2>
              <p style="margin:0 0 10px;">这封邮件来自 [(${site.title})] 的邮件中心。</p>
              <p style="margin:0 0 10px;color:#4b5563;">
                能看到这段文字，说明 SMTP 连接、账号密码和发件人都已经就绪，可以回到后台正常发信了。
              </p>
              <p style="margin:16px 0 0;padding-top:14px;border-top:1px solid #f3f4f6;\
            color:#9ca3af;font-size:12px;">
                发送时间 [(${site.url})]
              </p>
            </div>
            """;
        var raw = "发信配置可用。这封邮件来自 [(${site.title})] 的邮件中心，"
            + "能看到这段文字说明 SMTP 连接已经就绪。";
        return new Defaults("测试邮件", "点「发送测试」时使用的邮件内容", "【邮件中心】发信测试", html, raw);
    }

    /** Creates the records this plugin owns when they are missing. Never overwrites. */
    public void provision() {
        if (client.fetch(MailTemplate.class, TEST_NAME).isPresent()) {
            return;
        }
        var defaults = defaultsOf(TEST_KEY);
        var template = new MailTemplate();
        var metadata = new Metadata();
        metadata.setName(TEST_NAME);
        template.setMetadata(metadata);
        MailCenterLabels.markSystem(template, TEST_KEY);
        var spec = new MailTemplate.Spec();
        spec.setDisplayName(defaults.displayName());
        spec.setDescription(defaults.description());
        spec.setSubject(defaults.subject());
        spec.setContent(defaults.html());
        spec.setRawContent(defaults.raw());
        spec.setVariables(List.of());
        spec.setEnabled(true);
        template.setSpec(spec);
        try {
            client.create(template);
            log.info("Created the built in test mail template");
        } catch (Exception e) {
            log.debug("The built in test mail template already exists", e);
        }
    }

    public List<MailDto.SystemTemplate> list() {
        return List.of(TEST_KEY).stream().map(this::view).toList();
    }

    /** Current content of a system template, falling back to what the plugin ships. */
    public MailDto.SystemTemplate view(String key) {
        var defaults = defaultsOf(key);
        var stored = client.fetch(MailTemplate.class, nameOf(key));
        var spec = stored.map(MailTemplate::getSpec).orElse(null);
        var subject = spec == null || spec.getSubject() == null ? defaults.subject() : spec.getSubject();
        var html = spec == null || spec.getContent() == null ? defaults.html() : spec.getContent();
        var raw = spec == null || spec.getRawContent() == null ? defaults.raw() : spec.getRawContent();
        var displayName = spec == null || spec.getDisplayName() == null
            ? defaults.displayName() : spec.getDisplayName();
        var description = spec == null || spec.getDescription() == null
            ? defaults.description() : spec.getDescription();
        return new MailDto.SystemTemplate(key, nameOf(key), displayName, description, subject, html,
            raw, variablesOf(stored.orElse(null)), isEdited(key, subject, html, raw));
    }

    public MailDto.SystemTemplate update(String key, MailDto.SystemTemplatePayload payload) {
        if (payload == null) {
            throw new IllegalArgumentException("缺少模板内容");
        }
        if (payload.subject() == null || payload.subject().isBlank()) {
            throw new IllegalArgumentException("邮件标题不能为空");
        }
        if ((payload.htmlBody() == null || payload.htmlBody().isBlank())
            && (payload.rawBody() == null || payload.rawBody().isBlank())) {
            throw new IllegalArgumentException("HTML 正文和纯文本正文至少要填一个");
        }
        var template = client.fetch(MailTemplate.class, nameOf(key)).orElseGet(() -> {
            provision();
            return client.fetch(MailTemplate.class, nameOf(key))
                .orElseThrow(() -> new IllegalArgumentException("系统模板不可用：" + key));
        });
        var spec = template.getSpec() == null ? new MailTemplate.Spec() : template.getSpec();
        spec.setSubject(payload.subject().trim());
        spec.setContent(payload.htmlBody());
        spec.setRawContent(payload.rawBody());
        spec.setEnabled(true);
        template.setSpec(spec);
        client.update(template);
        return view(key);
    }

    /** Restores the shipped content; the record itself stays, so nothing has to be recreated. */
    public MailDto.SystemTemplate reset(String key) {
        var defaults = defaultsOf(key);
        return update(key, new MailDto.SystemTemplatePayload(defaults.subject(), defaults.html(),
            defaults.raw()));
    }

    /** Body to render for a system template, never null even before the record exists. */
    public Defaults resolve(String key) {
        var view = view(key);
        return new Defaults(view.displayName(), view.description(), view.subject(),
            view.htmlBody(), view.rawBody());
    }

    public static String nameOf(String key) {
        return MailTemplateCatalogService.SYSTEM_PREFIX + key;
    }

    private boolean isEdited(String key, String subject, String html, String raw) {
        var defaults = defaultsOf(key);
        return !Objects.equals(defaults.subject(), subject)
            || !Objects.equals(defaults.html(), html)
            || !Objects.equals(defaults.raw(), raw);
    }

    private List<MailDto.TemplateVariable> variablesOf(MailTemplate template) {
        if (template == null || template.getSpec() == null || template.getSpec().getVariables() == null) {
            return List.of();
        }
        var variables = new ArrayList<MailDto.TemplateVariable>();
        for (var variable : template.getSpec().getVariables()) {
            variables.add(new MailDto.TemplateVariable(variable.getName(),
                variable.getLabel() == null ? variable.getName() : variable.getLabel(),
                variable.getType() == null ? "string" : variable.getType(),
                variable.getSampleValue(), Boolean.TRUE.equals(variable.getRequired())));
        }
        return variables;
    }

    /** Key to template, used by the bundle export. */
    public Map<String, MailDto.SystemTemplate> byKey() {
        Map<String, MailDto.SystemTemplate> views = new LinkedHashMap<>();
        list().forEach(view -> views.put(view.key(), view));
        return views;
    }

    public Optional<MailTemplate> findRecord(String key) {
        return client.fetch(MailTemplate.class, nameOf(key));
    }
}