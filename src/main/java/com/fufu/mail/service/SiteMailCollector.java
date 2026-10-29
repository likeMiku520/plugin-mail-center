package com.fufu.mail.service;

import com.fufu.mail.model.MailSendLog;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.notification.Notification;
import run.halo.app.core.extension.notification.Reason;
import run.halo.app.core.extension.notification.ReasonType;
import run.halo.app.extension.ExtensionClient;

/**
 * Copies the notifications the site delivers into the mail log.
 *
 * <p>Halo sends its own mail through an internal notifier and offers plugins no hook on the
 * SMTP call, so the closest observable thing is the {@link Notification} record created for
 * every delivery to a registered user. Rows written here are marked as coming from the site,
 * and their status means "the site handled this" rather than "this plugin saw the mail leave".
 * Mail addressed to a visitor who never registered leaves no record at all.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SiteMailCollector {

    /** Marks records that describe mail the site sent on its own. */
    public static final String SOURCE = "SITE";

    public static final String TEMPLATE_TYPE = "NOTIFICATION";

    private static final int BATCH = 100;

    private final ExtensionClient client;
    private final MailDispatchService dispatch;

    private Instant lastRunAt;

    /** Imports notifications newer than the look back window, skipping recorded ones. */
    public void collect(int lookbackHours) {
        var now = Instant.now();
        if (lastRunAt != null && Duration.between(lastRunAt, now).getSeconds() < 30) {
            return;
        }
        lastRunAt = now;
        var since = now.minus(Duration.ofHours(Math.max(lookbackHours, 1)));
        List<Notification> notifications;
        try {
            notifications = client.list(Notification.class,
                notification -> createdAfter(notification, since), null);
        } catch (Exception e) {
            log.debug("Notifications are unreadable, skipping the site mail import", e);
            return;
        }
        if (notifications.isEmpty()) {
            return;
        }
        var imported = 0;
        var batch = notifications.stream()
            .filter(notification -> notification.getSpec() != null)
            .sorted(Comparator.comparing(SiteMailCollector::createdAt,
                Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
            .limit(BATCH)
            .toList();
        for (var notification : batch) {
            try {
                if (importOne(notification)) {
                    imported++;
                }
            } catch (Exception e) {
                log.debug("Skipped a notification that could not be imported", e);
            }
        }
        if (imported > 0) {
            log.info("Imported {} site notifications into the mail log", imported);
        }
    }

    private boolean importOne(Notification notification) {
        var name = notification.getMetadata() == null ? null : notification.getMetadata().getName();
        if (name == null) {
            return false;
        }
        var dedupKey = "site|" + name;
        var logName = MailDispatchService.deterministicName(dedupKey);
        if (client.fetch(MailSendLog.class, logName).isPresent()) {
            return false;
        }
        var spec = notification.getSpec();
        var recipient = spec.getRecipient();
        if (recipient == null) {
            return false;
        }
        var user = client.fetch(User.class, recipient).orElse(null);
        if (user == null || user.getSpec() == null) {
            return false;
        }
        var email = user.getSpec().getEmail();
        if (email == null || email.isBlank()) {
            return false;
        }
        String reasonTypeName = null;
        if (spec.getReason() != null) {
            reasonTypeName = client.fetch(Reason.class, spec.getReason())
                .map(Reason::getSpec)
                .map(Reason.Spec::getReasonType)
                .orElse(null);
        }
        var reasonDisplay = reasonTypeName;
        if (reasonTypeName != null) {
            var reasonType = client.fetch(ReasonType.class, reasonTypeName).orElse(null);
            if (reasonType != null && reasonType.getSpec() != null
                && reasonType.getSpec().getDisplayName() != null) {
                reasonDisplay = reasonType.getSpec().getDisplayName();
            }
        }
        var recipientName = user.getSpec().getDisplayName() == null
            ? email : user.getSpec().getDisplayName();
        dispatch.recordNote(email, recipientName, spec.getTitle(),
            MailDispatchService.STATUS_SENT_BY_SITE, null, SOURCE, TEMPLATE_TYPE,
            reasonTypeName == null ? "site-notification" : reasonTypeName,
            reasonDisplay == null ? "站点通知" : reasonDisplay,
            null, dedupKey, createdAt(notification));
        return true;
    }

    private static boolean createdAfter(Notification notification, Instant since) {
        var createdAt = createdAt(notification);
        return createdAt != null && createdAt.isAfter(since);
    }

    private static Instant createdAt(Notification notification) {
        return notification.getMetadata() == null ? null
            : notification.getMetadata().getCreationTimestamp();
    }
}