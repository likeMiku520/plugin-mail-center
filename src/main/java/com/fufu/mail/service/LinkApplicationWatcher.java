package com.fufu.mail.service;

import com.fufu.mail.dto.MailDto;
import com.fufu.mail.model.MailTrigger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.extension.Extension;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.Watcher;

/**
 * Reacts to the links plugin deciding on a friend link application. A watch is used
 * rather than a scan because the decision is an event, not a timestamp: watching only
 * sees future transitions, so switching this on never mails the history of old
 * applications.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LinkApplicationWatcher implements Watcher {

    private final ExtensionClient client;
    private final MailTriggerService triggerService;
    private final MailDispatchService dispatch;

    private volatile boolean disposed;
    private ExecutorService worker;

    public void start() {
        if (worker != null) {
            return;
        }
        worker = Executors.newSingleThreadExecutor(runnable -> {
            var thread = new Thread(runnable, "mail-center-link");
            thread.setDaemon(true);
            return thread;
        });
        disposed = false;
        client.watch(this);
        log.info("Mail center link application watcher started");
    }

    public void stop() {
        dispose();
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
    }

    @Override
    public boolean isDisposed() {
        return disposed;
    }

    @Override
    public void dispose() {
        disposed = true;
    }

    /**
     * Runs on Halo's extension flow, so it must never throw and must never block: the
     * work is filtered here and handed to this plugin's own thread.
     */
    @Override
    public void onUpdate(Extension oldExtension, Extension newExtension) {
        try {
            if (disposed) {
                return;
            }
            if (!LinkApplications.isLinkApplication(newExtension)) {
                return;
            }
            var previous = LinkApplications.toView(oldExtension);
            var current = LinkApplications.toView(newExtension);
            if (current == null) {
                log.warn("Link application update was unreadable: {}",
                    newExtension.getMetadata() == null ? "unknown"
                        : newExtension.getMetadata().getName());
                return;
            }
            log.info("Link application {} moved from {} to {}", current.name(),
                previous == null ? "(new)" : previous.status(), current.status());
            if (!LinkApplications.isDecided(current.status())) {
                return;
            }
            var from = previous == null ? null : previous.status();
            if (current.status().equals(from)) {
                return;
            }
            var executor = worker;
            if (executor == null) {
                log.warn("Link application {} was decided but the worker is gone", current.name());
                return;
            }
            executor.submit(() -> {
                try {
                    handle(current);
                } catch (Exception e) {
                    log.warn("Failed to answer the link application [{}]", current.name(), e);
                }
            });
        } catch (Exception e) {
            log.warn("Ignored an unreadable link application update", e);
        }
    }

    void handle(MailDto.LinkApplicationView application) {
        var eventType = LinkApplications.STATUS_APPROVED.equals(application.status())
            ? MailTrigger.EventType.LINK_APPROVED : MailTrigger.EventType.LINK_REJECTED;
        var triggers = triggerService.listEnabled().stream()
            .filter(trigger -> trigger.getSpec() != null && trigger.getSpec().getEventType() == eventType)
            .toList();
        if (triggers.isEmpty()) {
            return;
        }
        var variables = variablesOf(application);
        var actorEmail = applicantEmail(application);
        for (var trigger : triggers) {
            var triggerName = trigger.getMetadata().getName();
            var emails = resolveRecipients(trigger.getSpec(), actorEmail);
            if (emails.isEmpty()) {
                log.info("Link application [{}] matched rule [{}] but has no address to answer",
                    application.name(), triggerName);
                note(trigger, application, null, "这条规则匹配到了友链申请，但没有可用的收件地址："
                    + "申请里没填邮箱，评论来源的作者也不是邮箱身份。");
                continue;
            }
            var templateType = trigger.getSpec().getTemplateType() == null
                ? "BUILTIN" : trigger.getSpec().getTemplateType().name();
            MailDispatchService.ResolvedTemplate template;
            try {
                template = dispatch.resolve(templateType, trigger.getSpec().getTemplateName());
            } catch (RuntimeException e) {
                log.warn("Link rule [{}] points at a template that is gone: {}", triggerName,
                    trigger.getSpec().getTemplateName());
                note(trigger, application, emails.get(0), "规则指向的邮件模板已经不存在："
                    + trigger.getSpec().getTemplateName() + "，请到「邮件中心」重新选一个模板。");
                continue;
            }
            int sent = 0;
            int failed = 0;
            int total = 0;
            for (var email : emails) {
                var dedupKey = triggerName + '|' + eventType.name() + '|'
                    + application.name() + '|' + email;
                var result = dispatch.deliver(template, email, application.displayName(), variables,
                    null, "TRIGGER", triggerName, dedupKey);
                total++;
                if (result.success()) {
                    sent++;
                } else {
                    failed++;
                }
            }
            if (total > 0) {
                triggerService.recordRun(triggerName,
                    new MailDto.SendSummary(total, sent, failed, List.of()),
                    "最近一次命中：" + application.displayName());
            }
        }
    }

    /** Leaves a visible trace when a decided application could not be answered. */
    private void note(MailTrigger trigger, MailDto.LinkApplicationView application, String email,
                      String reason) {
        var spec = trigger.getSpec();
        var dedupKey = trigger.getMetadata().getName() + "|SKIPPED|" + application.name();
        dispatch.recordNote(email, application.displayName(), null,
            MailDispatchService.STATUS_SKIPPED, reason, "TRIGGER",
            spec.getTemplateType() == null ? "CUSTOM" : spec.getTemplateType().name(),
            spec.getTemplateName(), null, trigger.getMetadata().getName(), dedupKey, null);
    }

    /** Values a friend link template can use. */
    Map<String, Object> variablesOf(MailDto.LinkApplicationView application) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("displayName", application.displayName());
        variables.put("websiteUrl", application.url());
        variables.put("websiteDescription", application.description());
        variables.put("websiteLogo", application.logo());
        variables.put("backlink", application.backlink());
        variables.put("applicantEmail", application.email());
        variables.put("reviewStatus", application.status());
        variables.put("approved", LinkApplications.STATUS_APPROVED.equals(application.status()));
        variables.put("reviewResult", LinkApplications.STATUS_APPROVED.equals(application.status())
            ? "已通过" : "未通过");
        return variables;
    }

    /**
     * The address to answer. Applications submitted from a comment may carry no email,
     * so the source comment's author is used as a fallback.
     */
    String applicantEmail(MailDto.LinkApplicationView application) {
        var email = MailDispatchService.normalizeEmail(application.email());
        if (email != null) {
            return email;
        }
        if (application.originCommentName() == null || application.originCommentName().isBlank()) {
            return null;
        }
        return client.fetch(Comment.class, application.originCommentName())
            .map(Comment::getSpec)
            .map(Comment.CommentSpec::getOwner)
            .filter(owner -> Comment.CommentOwner.KIND_EMAIL.equalsIgnoreCase(owner.getKind()))
            .map(Comment.CommentOwner::getName)
            .map(MailDispatchService::normalizeEmail)
            .orElse(null);
    }

    List<String> resolveRecipients(MailTrigger.Spec spec, String applicantEmail) {
        Map<String, String> unique = new LinkedHashMap<>();
        var strategy = spec.getRecipientStrategy() == null
            ? MailTrigger.RecipientStrategy.EVENT_ACTOR : spec.getRecipientStrategy();
        if (strategy != MailTrigger.RecipientStrategy.FIXED) {
            addIfValid(unique, applicantEmail);
        }
        TriggerRecipients.explicit(spec).forEach(email -> addIfValid(unique, email));
        return List.copyOf(unique.values());
    }

    private static void addIfValid(Map<String, String> unique, String email) {
        TriggerRecipients.add(unique, email);
    }

    /** Waits for the queue to drain, used by tests and by a graceful stop. */
    void awaitIdle(long timeoutMillis) {
        var executor = worker;
        if (executor == null) {
            return;
        }
        try {
            executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}