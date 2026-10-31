package com.fufu.mail.service;

import com.fufu.mail.MailCenterSettings;
import com.fufu.mail.dto.MailDto;
import com.fufu.mail.model.MailSendLog;
import com.fufu.mail.model.MailTrigger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.SinglePage;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * Background worker behind the automatic rules. Rather than hooking Halo's event bus,
 * it scans content created inside a look back window and de-duplicates through the send
 * records, so a restart, a redeploy or a slow scan can never deliver the same mail twice.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailTriggerPoller {

    private static final int SCAN_PAGE_SIZE = 100;
    private static final int BASE_TICK_SECONDS = 15;
    private static final Duration SETTING_TIMEOUT = Duration.ofSeconds(5);

    private static final ExpressionParser EXPRESSION_PARSER = new SpelExpressionParser();

    private final ExtensionClient client;
    private final ReactiveSettingFetcher settingFetcher;
    private final MailTriggerService triggerService;
    private final MailDispatchService dispatch;
    private final MailRecipientService recipients;
    private final SiteMailCollector siteMailCollector;

    private ScheduledExecutorService scheduler;
    private volatile Instant lastScanAt;

    public void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "mail-center-trigger");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::tickSafely, 20, BASE_TICK_SECONDS, TimeUnit.SECONDS);
        log.info("Mail center trigger worker started");
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /** Exposed so the console can show the same values the worker uses. */
    public MailCenterSettings settings() {
        try {
            return settingFetcher.fetch(MailCenterSettings.GROUP, MailCenterSettings.class)
                .blockOptional(SETTING_TIMEOUT)
                .orElseGet(MailCenterSettings::empty);
        } catch (Exception e) {
            log.debug("Plugin settings are unreadable, using defaults", e);
            return MailCenterSettings.empty();
        }
    }

    void tickSafely() {
        try {
            tick();
        } catch (Exception e) {
            // A single bad rule must never stop the worker.
            log.warn("Mail trigger scan failed", e);
        }
    }

    void tick() {
        var settings = settings();
        var now = Instant.now();
        if (lastScanAt != null
            && Duration.between(lastScanAt, now).getSeconds() < settings.triggerIntervalSecondsOrDefault()) {
            return;
        }
        lastScanAt = now;
        siteMailCollector.collect(settings.triggerLookbackHoursOrDefault());
        var enabled = triggerService.listEnabled();
        if (enabled.isEmpty()) {
            return;
        }
        var since = now.minus(Duration.ofHours(settings.triggerLookbackHoursOrDefault()));
        if (hasEvent(enabled, MailTrigger.EventType.COMMENT_CREATED)) {
            scanComments(enabled, settings, since);
        }
        if (hasEvent(enabled, MailTrigger.EventType.USER_REGISTERED)) {
            scanUsers(enabled, since);
        }
        if (hasEvent(enabled, MailTrigger.EventType.POST_PUBLISHED)) {
            scanPosts(enabled, since);
        }
    }

    private static boolean hasEvent(List<MailTrigger> triggers, MailTrigger.EventType type) {
        return triggers.stream().anyMatch(trigger -> trigger.getSpec() != null
            && trigger.getSpec().getEventType() == type);
    }

    private List<MailTrigger> triggersFor(List<MailTrigger> triggers, MailTrigger.EventType type) {
        return triggers.stream()
            .filter(trigger -> trigger.getSpec() != null && trigger.getSpec().getEventType() == type)
            .toList();
    }

    void scanComments(List<MailTrigger> triggers, MailCenterSettings settings, Instant since) {
        var matching = triggersFor(triggers, MailTrigger.EventType.COMMENT_CREATED);
        var page = client.listBy(Comment.class, new ListOptions(),
            PageRequestImpl.of(1, SCAN_PAGE_SIZE, ExtensionUtil.defaultSort()));
        for (var comment : page.getItems()) {
            var createdAt = comment.getMetadata().getCreationTimestamp();
            if (createdAt == null || createdAt.isBefore(since)) {
                break;
            }
            var spec = comment.getSpec();
            if (spec == null || spec.getOwner() == null) {
                continue;
            }
            if (settings.notifyOnlyApprovedCommentsOrDefault() && !Boolean.TRUE.equals(spec.getApproved())) {
                continue;
            }
            var variables = commentVariables(comment);
            if (variables == null) {
                continue;
            }
            var subjectName = comment.getMetadata().getName();
            for (var trigger : matching) {
                if (trigger.getSpec().getStartedAt() != null
                    && createdAt.isBefore(trigger.getSpec().getStartedAt())) {
                    continue;
                }
                run(trigger, MailTrigger.EventType.COMMENT_CREATED, subjectName, variables,
                    actorEmailOfComment(comment), postOwnerOfComment(comment));
            }
        }
    }

    void scanUsers(List<MailTrigger> triggers, Instant since) {
        var matching = triggersFor(triggers, MailTrigger.EventType.USER_REGISTERED);
        var page = client.listBy(User.class, new ListOptions(),
            PageRequestImpl.of(1, SCAN_PAGE_SIZE, ExtensionUtil.defaultSort()));
        for (var user : page.getItems()) {
            var createdAt = user.getMetadata().getCreationTimestamp();
            if (createdAt == null || createdAt.isBefore(since)) {
                break;
            }
            var spec = user.getSpec();
            if (spec == null || spec.getEmail() == null || spec.getEmail().isBlank()) {
                continue;
            }
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("username", user.getMetadata().getName());
            variables.put("displayName", spec.getDisplayName());
            variables.put("email", spec.getEmail());
            variables.put("registeredAt", createdAt.toString());
            for (var trigger : matching) {
                if (trigger.getSpec().getStartedAt() != null
                    && createdAt.isBefore(trigger.getSpec().getStartedAt())) {
                    continue;
                }
                run(trigger, MailTrigger.EventType.USER_REGISTERED, user.getMetadata().getName(),
                    variables, spec.getEmail(), null);
            }
        }
    }

    void scanPosts(List<MailTrigger> triggers, Instant since) {
        var matching = triggersFor(triggers, MailTrigger.EventType.POST_PUBLISHED);
        var page = client.listBy(Post.class, new ListOptions(),
            PageRequestImpl.of(1, SCAN_PAGE_SIZE, ExtensionUtil.defaultSort()));
        for (var post : page.getItems()) {
            var spec = post.getSpec();
            if (spec == null || !Boolean.TRUE.equals(spec.getPublish())) {
                continue;
            }
            var publishedAt = spec.getPublishTime() != null
                ? spec.getPublishTime() : post.getMetadata().getCreationTimestamp();
            if (publishedAt == null || publishedAt.isBefore(since)) {
                continue;
            }
            var permalink = post.getStatus() == null ? null : post.getStatus().getPermalink();
            var excerpt = post.getStatus() == null ? null : post.getStatus().getExcerpt();
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("postName", post.getMetadata().getName());
            variables.put("postTitle", spec.getTitle());
            variables.put("postUrl", permalink);
            variables.put("postAuthor", recipients.displayNameOfUser(spec.getOwner()));
            variables.put("postSummary", excerpt);
            variables.put("subjectTitle", spec.getTitle());
            variables.put("subjectUrl", permalink);
            var authorEmail = recipients.emailOfUser(spec.getOwner());
            for (var trigger : matching) {
                if (trigger.getSpec().getStartedAt() != null
                    && publishedAt.isBefore(trigger.getSpec().getStartedAt())) {
                    continue;
                }
                run(trigger, MailTrigger.EventType.POST_PUBLISHED, post.getMetadata().getName(),
                    variables, authorEmail, authorEmail);
            }
        }
    }

    /** Evaluates one rule against one event and delivers to every resolved address. */
    void run(MailTrigger trigger, MailTrigger.EventType eventType, String subjectName,
             Map<String, Object> variables, String actorEmail, String postOwnerEmail) {
        var spec = trigger.getSpec();
        if (spec == null) {
            return;
        }
        if (!matchesCondition(spec.getCondition(), variables)) {
            return;
        }
        var emails = resolveRecipients(spec, actorEmail, postOwnerEmail);
        if (emails.isEmpty()) {
            return;
        }
        var templateType = spec.getTemplateType() == null ? "BUILTIN" : spec.getTemplateType().name();
        MailDispatchService.ResolvedTemplate template;
        try {
            template = dispatch.resolve(templateType, spec.getTemplateName());
        } catch (RuntimeException e) {
            log.debug("Trigger [{}] references a template that no longer exists",
                trigger.getMetadata().getName(), e);
            return;
        }
        int sent = 0;
        int failed = 0;
        int total = 0;
        for (var email : emails) {
            var dedupKey = trigger.getMetadata().getName() + '|' + eventType.name() + '|'
                + subjectName + '|' + email;
            var logName = MailDispatchService.deterministicName(dedupKey);
            if (client.fetch(MailSendLog.class, logName).isPresent()) {
                continue;
            }
            var result = dispatch.deliver(template, email, null, variables, null, "TRIGGER",
                trigger.getMetadata().getName(), dedupKey);
            total++;
            if (result.success()) {
                sent++;
            } else {
                failed++;
            }
        }
        if (total > 0) {
            triggerService.recordRun(trigger.getMetadata().getName(),
                new MailDto.SendSummary(total, sent, failed, List.of()),
                "最近一次命中：" + subjectName);
        }
    }

    Set<String> resolveRecipients(MailTrigger.Spec spec, String actorEmail, String postOwnerEmail) {
        return TriggerRecipients.resolve(spec, actorEmail, postOwnerEmail);
    }

    /**
     * Optional guard written by the operator, evaluated against the event variables.
     * Variables are exposed as SpEL variables, so a condition reads like
     * {@code #content.contains('价格')} or {@code #commenter == '某某'}.
     */
    boolean matchesCondition(String condition, Map<String, Object> variables) {
        if (condition == null || condition.isBlank()) {
            return true;
        }
        try {
            var context = SimpleEvaluationContext.forReadOnlyDataBinding()
                .withInstanceMethods()
                .build();
            variables.forEach((name, value) -> {
                if (isIdentifier(name)) {
                    context.setVariable(name, value);
                }
            });
            return Boolean.TRUE.equals(EXPRESSION_PARSER.parseExpression(condition)
                .getValue(context, Boolean.class));
        } catch (Exception e) {
            log.debug("Trigger condition [{}] could not be evaluated", condition, e);
            return false;
        }
    }

    private static boolean isIdentifier(String name) {
        if (name == null || name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (var index = 1; index < name.length(); index++) {
            if (!Character.isJavaIdentifierPart(name.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private String actorEmailOfComment(Comment comment) {
        var owner = comment.getSpec().getOwner();
        if (Comment.CommentOwner.KIND_EMAIL.equalsIgnoreCase(owner.getKind())) {
            return owner.getName();
        }
        return recipients.emailOfUser(owner.getName());
    }

    private String postOwnerOfComment(Comment comment) {
        var ref = comment.getSpec().getSubjectRef();
        if (ref == null || ref.getName() == null) {
            return null;
        }
        if (Post.KIND.equals(ref.getKind())) {
            return client.fetch(Post.class, ref.getName())
                .map(post -> post.getSpec() == null ? null : post.getSpec().getOwner())
                .map(recipients::emailOfUser)
                .orElse(null);
        }
        if (SinglePage.KIND.equals(ref.getKind())) {
            return client.fetch(SinglePage.class, ref.getName())
                .map(page -> page.getSpec() == null ? null : page.getSpec().getOwner())
                .map(recipients::emailOfUser)
                .orElse(null);
        }
        return null;
    }

    /** Variables handed to a template for one comment, named after Halo's own attributes. */
    Map<String, Object> commentVariables(Comment comment) {
        var spec = comment.getSpec();
        var ref = spec.getSubjectRef();
        var variables = new LinkedHashMap<String, Object>();
        var commenter = spec.getOwner().getDisplayName() == null
            ? spec.getOwner().getName() : spec.getOwner().getDisplayName();
        variables.put("commenter", commenter);
        variables.put("commenterEmail", actorEmailOfComment(comment));
        variables.put("commentName", comment.getMetadata().getName());
        variables.put("content", MailRenderService.toPlainText(spec.getContent()));
        variables.put("commentContent", MailRenderService.toPlainText(spec.getContent()));

        if (ref == null || ref.getName() == null) {
            return variables;
        }
        if (Post.KIND.equals(ref.getKind())) {
            var post = client.fetch(Post.class, ref.getName()).orElse(null);
            if (post == null || post.getSpec() == null) {
                return null;
            }
            var title = post.getSpec().getTitle();
            var url = post.getStatus() == null ? null : post.getStatus().getPermalink();
            variables.put("postName", post.getMetadata().getName());
            variables.put("postTitle", title);
            variables.put("postUrl", url);
            variables.put("postOwner", post.getSpec().getOwner());
            variables.put("subjectTitle", title);
            variables.put("subjectUrl", url);
            return variables;
        }
        if (SinglePage.KIND.equals(ref.getKind())) {
            var page = client.fetch(SinglePage.class, ref.getName()).orElse(null);
            if (page == null || page.getSpec() == null) {
                return null;
            }
            var title = page.getSpec().getTitle();
            var url = page.getStatus() == null ? null : page.getStatus().getPermalink();
            variables.put("pageName", page.getMetadata().getName());
            variables.put("pageTitle", title);
            variables.put("pageUrl", url);
            variables.put("pageOwner", page.getSpec().getOwner());
            variables.put("subjectTitle", title);
            variables.put("subjectUrl", url);
            return variables;
        }
        return variables;
    }

    /** Only used by tests and diagnostics. */
    List<String> describe(List<MailTrigger> triggers) {
        var names = new ArrayList<String>();
        triggers.forEach(trigger -> names.add(trigger.getMetadata().getName()));
        return names;
    }
}