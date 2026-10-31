package com.fufu.mail.service;

import com.fufu.mail.MailChannelStore;
import com.fufu.mail.dto.MailDto;
import com.fufu.mail.model.MailTrigger;
import java.util.List;
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

    private void createTemplateIfMissing(String name, MailDto.CustomTemplatePayload payload) {
        if (catalog.findCustom(name).isPresent()) {
            return;
        }
        catalog.createCustom(name, payload, true);
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

    private MailDto.CustomTemplatePayload approvedPayload() {
        var content = """
            <div style="max-width:560px;margin:0 auto;padding:28px 24px;border-radius:16px;\
            background:#f7fbfb;border:1px solid #e8eef2;font-family:-apple-system,\
            BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;\
            color:#0f172a;font-size:14px;line-height:1.8;">
              <h2 style="margin:0 0 16px;font-size:18px;font-weight:650;color:#23a79e;">友链申请已通过</h2>
              <p style="margin:0 0 12px;">[(${subscriber.displayName})] 你好：</p>
              <p style="margin:0 0 14px;">
                你在 <a href="[(${site.url})]" style="color:#23a79e;text-decoration:none;">\
            [(${site.title})]</a> 提交的友链申请已经审核通过，现在可以在友链页看到它了。
              </p>
              <div style="padding:14px 16px;margin:0 0 16px;background:#ffffff;\
            border:1px solid #e8eef2;border-radius:12px;">
                <div style="margin-bottom:6px;"><span style="color:#64748b;">站点名称：</span>\
            [(${displayName})]</div>
                <div><span style="color:#64748b;">站点地址：</span>\
            <a href="[(${websiteUrl})]" style="color:#23a79e;text-decoration:none;">[(${websiteUrl})]</a></div>
              </div>
              <p style="margin:0;">感谢你的信任，欢迎常来。</p>
              <p style="margin:18px 0 0;color:#94a3b8;font-size:12px;">[(${site.title})]</p>
            </div>
            """;
        var raw = """
            [(${subscriber.displayName})] 你好：

            你在 [(${site.title})] 提交的友链申请已经审核通过，现在可以在友链页看到它了。

            站点名称：[(${displayName})]
            站点地址：[(${websiteUrl})]

            感谢你的信任，欢迎常来。
            [(${site.title})]
            """;
        return new MailDto.CustomTemplatePayload(null, "友链审核通过回信",
            "友链申请审核通过后自动发给申请人，默认由本插件在检测到链接插件时创建。",
            "友链申请已通过：[(${site.title})]", content, raw,
            commonVariables(), true);
    }

    private MailDto.CustomTemplatePayload rejectedPayload() {
        var content = """
            <div style="max-width:560px;margin:0 auto;padding:28px 24px;border-radius:16px;\
            background:#f7fbfb;border:1px solid #e8eef2;font-family:-apple-system,\
            BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;\
            color:#0f172a;font-size:14px;line-height:1.8;">
              <h2 style="margin:0 0 16px;font-size:18px;font-weight:650;color:#23a79e;">友链申请审核结果</h2>
              <p style="margin:0 0 12px;">[(${subscriber.displayName})] 你好：</p>
              <p style="margin:0 0 14px;">
                感谢你在 <a href="[(${site.url})]" style="color:#23a79e;text-decoration:none;">\
            [(${site.title})]</a> 提交友链申请。这一次暂时没有通过审核，可能是内容方向不太一致，\
            或者站点暂时无法正常访问。
              </p>
              <div style="padding:14px 16px;margin:0 0 16px;background:#ffffff;\
            border:1px solid #e8eef2;border-radius:12px;">
                <div style="margin-bottom:6px;"><span style="color:#64748b;">站点名称：</span>\
            [(${displayName})]</div>
                <div><span style="color:#64748b;">站点地址：</span>\
            <a href="[(${websiteUrl})]" style="color:#23a79e;text-decoration:none;">[(${websiteUrl})]</a></div>
              </div>
              <p style="margin:0;">若站点之后有调整，欢迎再次提交。</p>
              <p style="margin:18px 0 0;color:#94a3b8;font-size:12px;">[(${site.title})]</p>
            </div>
            """;
        var raw = """
            [(${subscriber.displayName})] 你好：

            感谢你在 [(${site.title})] 提交友链申请。这一次暂时没有通过审核，
            可能是内容方向不太一致，或者站点暂时无法正常访问。

            站点名称：[(${displayName})]
            站点地址：[(${websiteUrl})]

            若站点之后有调整，欢迎再次提交。
            [(${site.title})]
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
            new MailDto.TemplateVariable("websiteUrl", "申请人填写的站点地址", "url",
                "https://example.com", false),
            new MailDto.TemplateVariable("websiteDescription", "申请人填写的站点简介", "string",
                "记录一些日常", false),
            new MailDto.TemplateVariable("backlink", "申请人的回链页面", "url",
                "https://example.com/links", false),
            new MailDto.TemplateVariable("applicantEmail", "申请人邮箱", "email",
                "friend@example.com", false));
    }
}