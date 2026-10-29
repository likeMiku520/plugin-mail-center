package com.fufu.mail.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * An automatic sending rule. The poller looks at content created after
 * {@code spec.startedAt}, so enabling a rule never fires for history.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
    group = MailTemplate.GROUP,
    version = MailTemplate.VERSION,
    kind = MailTrigger.KIND,
    plural = "mailtriggers",
    singular = "mailtrigger")
public class MailTrigger extends AbstractExtension {

    public static final String KIND = "MailTrigger";

    /**
     * Event a rule listens to. {@code COMMENT_CREATED} reacts to new comments,
     * {@code USER_REGISTERED} to freshly created accounts, {@code POST_PUBLISHED} to
     * posts that became public.
     */
    public enum EventType {
        COMMENT_CREATED,
        USER_REGISTERED,
        POST_PUBLISHED,
        LINK_APPROVED,
        LINK_REJECTED
    }

    /**
     * Who receives a triggered mail. {@code EVENT_ACTOR} is the person the event is
     * about, {@code POST_AUTHOR} the owner of the commented or published post,
     * {@code FIXED} only the addresses listed in {@code extraEmails}.
     */
    public enum RecipientStrategy {
        EVENT_ACTOR,
        POST_AUTHOR,
        FIXED
    }

    /** Whether the template name points at a Halo notification template or our own. */
    public enum TemplateType {
        BUILTIN,
        CUSTOM
    }

    @Schema
    private Spec spec;

    @Data
    @Schema(name = "MailTriggerSpec")
    public static class Spec {

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1)
        private String displayName;

        private Boolean enabled;

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private EventType eventType;

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private TemplateType templateType;

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1)
        private String templateName;

        private RecipientStrategy recipientStrategy;

        private List<String> extraEmails;

        /** Optional SpEL guard evaluated against the event variables. */
        private String condition;

        /** Content older than this instant is ignored, so enabling a rule is not retroactive. */
        private Instant startedAt;

        private Stats stats;
    }

    @Data
    @Schema(name = "MailTriggerStats")
    public static class Stats {

        private Long sent;

        private Long failed;

        private Long skipped;

        private Instant lastRunAt;

        private String lastMessage;
    }
}