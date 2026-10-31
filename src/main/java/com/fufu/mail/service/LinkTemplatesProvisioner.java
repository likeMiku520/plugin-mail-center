package com.fufu.mail.service;

import com.fufu.mail.MailCenterLabels;
import com.fufu.mail.MailChannelStore;
import com.fufu.mail.dto.MailDto;
import com.fufu.mail.model.MailTemplate;
import com.fufu.mail.model.MailTrigger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.Plugin;
import run.halo.app.extension.ExtensionClient;

/**
 * Answers friend link applications once the links plugin is around: two templates and
 * two rules, created the first time both this plugin and the links plugin are present.
 * Everything it creates is ordinary content — editable, disableable and deletable from
 * the console like anything else.
 *
 * <p>The default bodies follow the card layout used by the other notification mails on a
 * site: a soft rounded container, white inner blocks, one accent colour and a pill shaped
 * button. Link targets are written with {@code th:href}, because Thymeleaf does not expand
 * an inline expression inside an attribute value — written inline, the reader gets the
 * literal text as the target and the link goes nowhere.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LinkTemplatesProvisioner {

    public static final String LINKS_PLUGIN = "PluginLinks";

    public static final String APPROVED_TEMPLATE = "mail-template-friend-link-approved";
    public static final String REJECTED_TEMPLATE = "mail-template-friend-link-rejected";
    public static final String APPROVED_TRIGGER = "mail-trigger-friend-link-approved";
    public static final String REJECTED_TRIGGER = "mail-trigger-friend-link-rejected";

    private final ExtensionClient client;
    private final MailTemplateCatalogService catalog;
    private final MailTriggerService triggerService;
    private final MailChannelStore channelStore;

    public boolean linksPluginPresent() {
        return client.fetch(Plugin.class, LINKS_PLUGIN).isPresent();
    }

    /**
     * Creates the defaults once the links plugin is installed. It runs again only when the
     * previous attempt never finished, which is how a half-written set from an interrupted
     * start gets repaired; once it has completed, deleting any of the four is respected.
     */
    public void provision() {
        try {
            if (!linksPluginPresent()) {
                return;
            }
            var channel = channelStore.load();
            if (channel.linkDefaultsSeededOrDefault()) {
                return;
            }
            createTemplateIfMissing(APPROVED_TEMPLATE, approvedPayload());
            createTemplateIfMissing(REJECTED_TEMPLATE, rejectedPayload());
            createTriggerIfMissing(APPROVED_TRIGGER, "友链申请通过后回信",
                MailTrigger.EventType.LINK_APPROVED, APPROVED_TEMPLATE);
            createTriggerIfMissing(REJECTED_TRIGGER, "友链申请拒绝后回信",
                MailTrigger.EventType.LINK_REJECTED, REJECTED_TEMPLATE);
            channelStore.save(channel.withLinkDefaultsSeeded());
            log.info("Provisioned friend link reply templates and rules");
        } catch (Exception e) {
            log.warn("Failed to provision friend link reply defaults", e);
        }
    }

    /**
     * Brings the reply templates up to the current default text, but only when the record
     * still holds exactly what an earlier release wrote. A template the operator edited
     * carries a different digest and is left untouched.
     */
    public void refreshDefaults() {
        for (var name : List.of(APPROVED_TEMPLATE, REJECTED_TEMPLATE)) {
            try {
                refreshOne(name);
            } catch (Exception e) {
                log.warn("Could not refresh the default body of {}", name, e);
            }
        }
    }

    private void refreshOne(String name) {
        var payload = APPROVED_TEMPLATE.equals(name) ? approvedPayload() : rejectedPayload();
        var template = catalog.findCustom(name).orElse(null);
        if (template == null || !MailCenterLabels.isAutoProvisioned(template)) {
            return;
        }
        var stored = MailCenterLabels.provisionedBody(template);
        if (stored == null) {
            // Written before bodies were stamped: could be an edit, so never overwrite.
            return;
        }
        if (!stored.equals(digestOf(template.getSpec()))) {
            log.debug("{} was edited after provisioning, keeping the operator's text", name);
            return;
        }
        var fresh = digestOf(payload);
        if (fresh == null || fresh.equals(stored)) {
            return;
        }
        catalog.replaceBody(name, payload);
        var updated = catalog.findCustom(name).orElse(null);
        if (updated != null) {
            MailCenterLabels.setProvisionedBody(updated, fresh);
            client.update(updated);
        }
        log.info("Refreshed the default body of {}", name);
    }

    private void createTemplateIfMissing(String name, MailDto.CustomTemplatePayload payload) {
        if (catalog.findCustom(name).isPresent()) {
            return;
        }
        catalog.createCustom(name, payload, true,
            Map.of(MailCenterLabels.PROVISIONED_BODY, String.valueOf(digestOf(payload))));
    }

    private void createTriggerIfMissing(String name, String displayName,
                                        MailTrigger.EventType eventType, String templateName) {
        if (triggerService.find(name).isPresent()) {
            return;
        }
        var payload = new MailDto.TriggerPayload(name, displayName, true, eventType.name(), "CUSTOM",
            templateName, MailTrigger.RecipientStrategy.EVENT_ACTOR.name(), List.of(), null,
            List.of());
        triggerService.create(payload, true);
    }

    static String digestOf(MailDto.CustomTemplatePayload payload) {
        return digest(payload.subject(), payload.content(), payload.rawContent());
    }

    static String digestOf(MailTemplate.Spec spec) {
        if (spec == null) {
            return null;
        }
        return digest(spec.getSubject(), spec.getContent(), spec.getRawContent());
    }

    /** Short stable digest of the three editable parts, used to spot an operator edit. */
    private static String digest(String subject, String html, String raw) {
        try {
            var digest = MessageDigest.getInstance("SHA-1");
            var text = String.join("\u0000", String.valueOf(subject), String.valueOf(html),
                String.valueOf(raw));
            var bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            var builder = new StringBuilder();
            for (var index = 0; index < 8; index++) {
                builder.append(String.format("%02x", bytes[index]));
            }
            return builder.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static final String CARD = "<div style=\"max-width:560px;margin:0 auto;padding:28px 24px;border-radius:16px;background:#f7fbfb;border:1px solid #e8eef2;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;color:#0f172a;font-size:14px;line-height:1.8;\">";
    private static final String HEADING = "<h2 style=\"margin:0 0 16px;font-size:18px;font-weight:650;color:#23a79e;\">";
    private static final String BLOCK = "<div style=\"padding:14px 16px;margin:0 0 16px;background:#ffffff;border:1px solid #e8eef2;border-radius:12px;\">";
    private static final String LABEL = "<span style=\"color:#64748b;\">";
    private static final String LINK = "style=\"color:#23a79e;text-decoration:none;word-break:break-all;\"";
    private static final String BUTTON_FILLED = "style=\"display:inline-block;padding:9px 20px;border-radius:9999px;background:#23a79e;color:#ffffff;text-decoration:none;font-size:14px;\"";
    private static final String BUTTON_OUTLINE = "style=\"display:inline-block;padding:9px 20px;border-radius:9999px;background:#ffffff;color:#23a79e;border:1px solid #23a79e;text-decoration:none;font-size:14px;\"";
    private static final String FOOTER = "<p style=\"margin:18px 0 0;color:#94a3b8;font-size:12px;\">本邮件由系统自动发送，请勿直接回复。</p>";

    MailDto.CustomTemplatePayload approvedPayload() {
        var content = """
            %s
              %s友链申请已通过</h2>
              <p style="margin:0 0 14px;">[(${subscriber.displayName})] 你好：</p>
              <p style="margin:0 0 14px;">你在 <a th:href="${site.url}" style="color:#23a79e;text-decoration:none;">[(${site.title})]</a> 提交的友链申请已经通过审核，友链页上已经能看到它了。</p>
              %s
                <div style="color:#64748b;margin-bottom:8px;">申请信息</div>
                <div style="margin-bottom:6px;">%s站点名称：</span>[(${displayName})]</div>
                <div style="margin-bottom:6px;word-break:break-all;">%s站点地址：</span><a th:href="${websiteUrl}" target="_blank" %s>[(${websiteUrl})]</a></div>
                <div th:if="${backlink}" style="word-break:break-all;">%s回链页面：</span><a th:href="${backlink}" target="_blank" %s>[(${backlink})]</a></div>
              </div>
              <p style="margin:0 0 18px;"><a th:href="${site.url}" target="_blank" %s>来看看 [(${site.title})]</a></p>
              <p style="margin:0;color:#64748b;">感谢你的信任，欢迎常来。</p>
              %s
            </div>
            """.formatted(CARD, HEADING, BLOCK, LABEL, LABEL, LINK, LABEL, LINK, BUTTON_FILLED, FOOTER);
        var raw = """
            [(${subscriber.displayName})] 你好：

            你在 [(${site.title})] 提交的友链申请已经通过审核，友链页上已经能看到它了。

            站点名称：[(${displayName})]
            站点地址：[(${websiteUrl})]

            感谢你的信任，欢迎常来。
            [(${site.url})]
            """;
        return new MailDto.CustomTemplatePayload(null, "友链审核通过回信",
            "友链申请审核通过后自动发给申请人，默认由本插件在检测到链接插件时创建。",
            "友链申请已通过：[(${site.title})]", content, raw,
            commonVariables(), true);
    }

    MailDto.CustomTemplatePayload rejectedPayload() {
        var content = """
            %s
              %s友链申请审核结果</h2>
              <p style="margin:0 0 14px;">[(${subscriber.displayName})] 你好：</p>
              <p style="margin:0 0 14px;">感谢你在 <a th:href="${site.url}" style="color:#23a79e;text-decoration:none;">[(${site.title})]</a> 提交友链申请。这一次暂时没有通过审核，可能是内容方向不太一致，或者站点暂时无法正常访问。</p>
              %s
                <div style="color:#64748b;margin-bottom:8px;">申请信息</div>
                <div style="margin-bottom:6px;">%s站点名称：</span>[(${displayName})]</div>
                <div style="word-break:break-all;">%s站点地址：</span><a th:href="${websiteUrl}" target="_blank" %s>[(${websiteUrl})]</a></div>
              </div>
              <p style="margin:0 0 18px;color:#64748b;">若站点之后有调整，欢迎再次提交。</p>
              <p style="margin:0;"><a th:href="${site.url}" target="_blank" %s>回到 [(${site.title})]</a></p>
              %s
            </div>
            """.formatted(CARD, HEADING, BLOCK, LABEL, LABEL, LINK, BUTTON_OUTLINE, FOOTER);
        var raw = """
            [(${subscriber.displayName})] 你好：

            感谢你在 [(${site.title})] 提交友链申请。这一次暂时没有通过审核，
            可能是内容方向不太一致，或者站点暂时无法正常访问。

            站点名称：[(${displayName})]
            站点地址：[(${websiteUrl})]

            若站点之后有调整，欢迎再次提交。
            [(${site.url})]
            """;
        return new MailDto.CustomTemplatePayload(null, "友链审核拒绝回信",
            "友链申请被拒绝后自动发给申请人，默认由本插件在检测到链接插件时创建。",
            "友链申请审核结果：[(${site.title})]", content, raw,
            commonVariables(), true);
    }

    private static List<MailDto.TemplateVariable> commonVariables() {
        return List.of(
            new MailDto.TemplateVariable("displayName", "申请人填写的站点名称", "string", "小明的博客",
                false),
            new MailDto.TemplateVariable("websiteUrl", "申请人填写的站点地址，发送时补全协议头", "url",
                "https://example.com", false),
            new MailDto.TemplateVariable("websiteDescription", "申请人填写的站点简介", "string",
                "记录一些日常", false),
            new MailDto.TemplateVariable("backlink", "申请人的回链页面，发送时补全协议头", "url",
                "https://example.com/links", false),
            new MailDto.TemplateVariable("applicantEmail", "申请人邮箱", "email",
                "friend@example.com", false));
    }
}