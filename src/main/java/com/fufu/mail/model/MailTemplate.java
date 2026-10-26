package com.fufu.mail.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * A standalone mail template created from the console. It is rendered with the same
 * Thymeleaf engine Halo uses for its own notifications, so the variables declared here
 * are the only ones a template author has to care about.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
    group = MailTemplate.GROUP,
    version = MailTemplate.VERSION,
    kind = MailTemplate.KIND,
    plural = "mailtemplates",
    singular = "mailtemplate")
public class MailTemplate extends AbstractExtension {

    public static final String GROUP = "mail.fufu520.cn";
    public static final String VERSION = "v1alpha1";
    public static final String KIND = "MailTemplate";

    @Schema
    private Spec spec;

    @Data
    @Schema(name = "MailTemplateSpec")
    public static class Spec {

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1)
        private String displayName;

        private String description;

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1)
        private String subject;

        /** HTML body. May be blank when only a plain text body is wanted. */
        private String content;

        /** Plain text alternative. Falls back to a stripped HTML body when blank. */
        private String rawContent;

        private List<Variable> variables;

        private Boolean enabled;
    }

    @Data
    @Schema(name = "MailTemplateVariable")
    public static class Variable {

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1)
        private String name;

        private String label;

        private String type;

        private String sampleValue;

        private Boolean required;
    }
}