package com.fufu.mail.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * One delivered (or failed) mail. Records are also the de-duplication ledger for
 * automatic triggers: a triggered mail derives a deterministic record name from
 * trigger plus subject plus recipient, so a restart can never send the same mail twice.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
    group = MailTemplate.GROUP,
    version = MailTemplate.VERSION,
    kind = MailSendLog.KIND,
    plural = "mailsendlogs",
    singular = "mailsendlog")
public class MailSendLog extends AbstractExtension {

    public static final String KIND = "MailSendLog";

    /** Name prefix shared by deterministic trigger records. */
    public static final String DETERMINISTIC_PREFIX = "mail-log-";

    @Schema
    private Spec spec;

    @Data
    @Schema(name = "MailSendLogSpec")
    public static class Spec {

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1)
        private String recipient;

        private String recipientName;

        private String templateType;

        private String templateName;

        private String templateDisplayName;

        private String subject;

        /** SUCCESS, FAILED, SKIPPED or SENT. */
        private String status;

        private String error;

        /** MANUAL, API, TRIGGER or SITE. */
        private String source;

        private String triggerName;

        private String dedupKey;

        private Instant sentAt;

        private Long durationMs;

        private String preview;

        /** The body that went out, kept so the console can show the mail as sent. */
        private String htmlBody;

        private String rawBody;
    }
}