package com.fufu.mail.service;

import com.fufu.mail.MailCenterLabels;
import com.fufu.mail.dto.MailDto;
import com.fufu.mail.model.MailTrigger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;

/**
 * Storage and validation for automatic sending rules. Rules never look backwards: a
 * rule carries the instant it was switched on, and the poller only considers content
 * created after it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailTriggerService {

    private final ExtensionClient client;
    private final MailDispatchService dispatch;

    public List<MailTrigger> listEnabled() {
        return client.listAll(MailTrigger.class, new ListOptions(), ExtensionUtil.defaultSort())
            .stream()
            .filter(trigger -> trigger.getSpec() != null
                && Boolean.TRUE.equals(trigger.getSpec().getEnabled()))
            .toList();
    }

    public List<MailDto.TriggerView> listViews() {
        return client.listAll(MailTrigger.class, new ListOptions(), ExtensionUtil.defaultSort())
            .stream()
            .map(this::toView)
            .toList();
    }

    public Optional<MailTrigger> find(String name) {
        return client.fetch(MailTrigger.class, name);
    }

    public MailTrigger create(MailDto.TriggerPayload payload) {
        return create(payload, false);
    }

    /**
     * Labels are written before the record is created: updating an object that was just
     * created would carry no resource version and Halo would treat it as an insert.
     */
    public MailTrigger create(MailDto.TriggerPayload payload, boolean autoProvisioned) {
        var trigger = new MailTrigger();
        var metadata = new Metadata();
        metadata.setName(payload.name() == null || payload.name().isBlank()
            ? MailTemplateCatalogService.uniqueName(
                payload.displayName() == null ? "trigger" : payload.displayName())
                .replace("mail-template-", "mail-trigger-")
            : payload.name());
        trigger.setMetadata(metadata);
        if (autoProvisioned) {
            MailCenterLabels.markAutoProvisioned(trigger);
        }
        trigger.setSpec(new MailTrigger.Spec());
        apply(trigger.getSpec(), payload, null);
        client.create(trigger);
        return trigger;
    }

    public MailTrigger update(String name, MailDto.TriggerPayload payload) {
        var trigger = client.fetch(MailTrigger.class, name)
            .orElseThrow(() -> new IllegalArgumentException("触发规则不存在：" + name));
        if (trigger.getSpec() == null) {
            trigger.setSpec(new MailTrigger.Spec());
        }
        apply(trigger.getSpec(), payload, trigger.getSpec().getStats());
        client.update(trigger);
        return trigger;
    }

    public void delete(String name) {
        client.fetch(MailTrigger.class, name).ifPresent(client::delete);
    }

    /** Keeps the counters on the rule so the console can show what it has done lately. */
    public void recordRun(String name, MailDto.SendSummary summary, String message) {
        client.fetch(MailTrigger.class, name).ifPresent(trigger -> {
            var spec = trigger.getSpec();
            if (spec == null) {
                return;
            }
            var stats = spec.getStats() == null ? new MailTrigger.Stats() : spec.getStats();
            stats.setSent((stats.getSent() == null ? 0L : stats.getSent()) + summary.success());
            stats.setFailed((stats.getFailed() == null ? 0L : stats.getFailed()) + summary.failed());
            stats.setSkipped((stats.getSkipped() == null ? 0L : stats.getSkipped()) + summary.total());
            stats.setLastRunAt(Instant.now());
            stats.setLastMessage(message);
            spec.setStats(stats);
            try {
                client.update(trigger);
            } catch (Exception e) {
                log.debug("Trigger [{}] was modified concurrently, counters skipped", name, e);
            }
        });
    }

    public MailDto.TriggerView toView(MailTrigger trigger) {
        var spec = trigger.getSpec();
        var stats = spec == null ? null : spec.getStats();
        var strategy = spec == null || spec.getRecipientStrategy() == null
            ? MailTrigger.RecipientStrategy.EVENT_ACTOR : spec.getRecipientStrategy();
        var displayName = spec == null || spec.getTemplateName() == null
            ? spec == null ? null : spec.getTemplateName()
            : dispatch.displayNameOf(spec.getTemplateType() == null ? "BUILTIN"
                : spec.getTemplateType().name(), spec.getTemplateName());
        var subject = spec == null ? null
            : dispatch.subjectOf(spec.getTemplateType() == null ? "BUILTIN"
                : spec.getTemplateType().name(), spec.getTemplateName());
        return new MailDto.TriggerView(
            trigger.getMetadata().getName(),
            spec == null ? null : spec.getDisplayName(),
            spec != null && Boolean.TRUE.equals(spec.getEnabled()),
            spec == null || spec.getEventType() == null ? null : spec.getEventType().name(),
            spec == null || spec.getTemplateType() == null ? null : spec.getTemplateType().name(),
            spec == null ? null : spec.getTemplateName(),
            displayName,
            subject,
            strategy.name(),
            spec == null || spec.getExtraEmails() == null ? List.of() : spec.getExtraEmails(),
            spec == null ? null : spec.getCondition(),
            spec == null || spec.getStartedAt() == null ? null : spec.getStartedAt().toString(),
            stats == null ? 0L : stats.getSent(),
            stats == null ? 0L : stats.getFailed(),
            stats == null ? 0L : stats.getSkipped(),
            stats == null || stats.getLastRunAt() == null ? null : stats.getLastRunAt().toString(),
            stats == null ? null : stats.getLastMessage(),
            spec == null || spec.getExtraRecipients() == null ? List.of()
                : spec.getExtraRecipients().stream()
                    .map(reference -> new MailDto.SendTarget(reference.getEmail(),
                        reference.getName()))
                    .toList());
    }

    private void apply(MailTrigger.Spec spec, MailDto.TriggerPayload payload,
                       MailTrigger.Stats existingStats) {
        if (payload.displayName() == null || payload.displayName().isBlank()) {
            throw new IllegalArgumentException("规则名称不能为空");
        }
        var templateType = parseTemplateType(payload.templateType());
        var templateName = payload.templateName();
        if (templateName == null || templateName.isBlank()) {
            throw new IllegalArgumentException("请选择要发送的邮件模板");
        }
        if (!dispatch.templateExists(templateType.name(), templateName)) {
            throw new IllegalArgumentException("选择的邮件模板已经不存在了，请重新选择");
        }
        var emails = new ArrayList<String>();
        if (payload.extraEmails() != null) {
            for (var email : payload.extraEmails()) {
                var normalized = MailDispatchService.normalizeEmail(email);
                if (normalized == null) {
                    throw new IllegalArgumentException("额外收件人邮箱不合法：" + email);
                }
                emails.add(normalized);
            }
        }
        var recipients = new LinkedHashMap<String, MailTrigger.RecipientRef>();
        if (payload.extraRecipients() != null) {
            for (var target : payload.extraRecipients()) {
                if (target == null) {
                    continue;
                }
                var normalized = MailDispatchService.normalizeEmail(target.email());
                if (normalized == null) {
                    throw new IllegalArgumentException("收件人邮箱不合法：" + target.email());
                }
                var reference = new MailTrigger.RecipientRef();
                reference.setEmail(normalized);
                reference.setName(target.name() == null || target.name().isBlank()
                    ? null : target.name().trim());
                recipients.putIfAbsent(normalized.toLowerCase(Locale.ROOT), reference);
            }
        }
        spec.setDisplayName(payload.displayName().trim());
        spec.setEventType(parseEventType(payload.eventType()));
        spec.setTemplateType(templateType);
        spec.setTemplateName(templateName);
        spec.setRecipientStrategy(parseStrategy(payload.recipientStrategy()));
        spec.setExtraEmails(emails);
        spec.setExtraRecipients(new ArrayList<>(recipients.values()));
        spec.setCondition(payload.condition() == null || payload.condition().isBlank()
            ? null : payload.condition().trim());
        spec.setStats(existingStats);

        var enabled = payload.enabled() == null || payload.enabled();
        var wasEnabled = Boolean.TRUE.equals(spec.getEnabled());
        spec.setEnabled(enabled);
        if (!enabled) {
            return;
        }
        if (!wasEnabled || spec.getStartedAt() == null) {
            // Switching a rule on starts its window now, so history is never replayed.
            spec.setStartedAt(Instant.now());
        }
    }

    static MailTrigger.EventType parseEventType(String value) {
        if (value == null || value.isBlank()) {
            return MailTrigger.EventType.COMMENT_CREATED;
        }
        try {
            return MailTrigger.EventType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的触发事件：" + value);
        }
    }

    static MailTrigger.TemplateType parseTemplateType(String value) {
        if (value == null || value.isBlank()) {
            return MailTrigger.TemplateType.BUILTIN;
        }
        try {
            return MailTrigger.TemplateType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的模板类型：" + value);
        }
    }

    static MailTrigger.RecipientStrategy parseStrategy(String value) {
        if (value == null || value.isBlank()) {
            return MailTrigger.RecipientStrategy.EVENT_ACTOR;
        }
        try {
            return MailTrigger.RecipientStrategy.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的收件人策略：" + value);
        }
    }
}