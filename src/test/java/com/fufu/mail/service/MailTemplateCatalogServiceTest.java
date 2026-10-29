package com.fufu.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import run.halo.app.core.extension.notification.NotificationTemplate;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.Metadata;

class MailTemplateCatalogServiceTest {

    @Test
    void buildsAReadableSlugFromLatinNames() {
        assertThat(MailTemplateCatalogService.slugify("Welcome Mail")).isEqualTo("welcome-mail");
        assertThat(MailTemplateCatalogService.slugify("  spaced  ")).isEqualTo("spaced");
        assertThat(MailTemplateCatalogService.slugify("a/b/c")).isEqualTo("a-b-c");
    }

    @Test
    void fallsBackForNamesWithoutLatinCharacters() {
        assertThat(MailTemplateCatalogService.slugify("欢迎邮件")).isEqualTo("custom");
        assertThat(MailTemplateCatalogService.slugify(null)).isEqualTo("custom");
    }

    @Test
    void keepsGeneratedNamesUniqueAndDnsSafe() {
        var first = MailTemplateCatalogService.uniqueName("Welcome Mail");
        var second = MailTemplateCatalogService.uniqueName("Welcome Mail");

        assertThat(first).startsWith("mail-template-welcome-mail-");
        assertThat(second).isNotEqualTo(first);
        assertThat(first).matches("[a-z0-9.-]{1,253}");
    }

    @Test
    void shortensVeryLongNames() {
        var name = MailTemplateCatalogService.uniqueName("x".repeat(200));
        assertThat(name.length()).isLessThanOrEqualTo(253);
        assertThat(name).startsWith("mail-template-");
    }

    @Test
    void suppliesATypeAwareSampleValue() {
        assertThat(MailTemplateCatalogService.sampleValue("boolean")).isEqualTo("true");
        assertThat(MailTemplateCatalogService.sampleValue("number")).isEqualTo("123");
        assertThat(MailTemplateCatalogService.sampleValue("url")).startsWith("https://");
        assertThat(MailTemplateCatalogService.sampleValue("email")).contains("@");
        assertThat(MailTemplateCatalogService.sampleValue(null)).isEqualTo("示例内容");
    }

    @Test
    void marksAnEditedSystemTemplateSoHaloStopsOverwritingIt() {
        var template = new NotificationTemplate();
        var metadata = new Metadata();
        metadata.setName("template-new-comment-on-post");
        template.setMetadata(metadata);

        MailTemplateCatalogService.markUserMaintained(template, true);

        assertThat(ExtensionUtil.hasDoNotOverwriteLabel(template)).isTrue();
        assertThat(template.getMetadata().getLabels())
            .containsEntry(ExtensionUtil.DO_NOT_OVERWRITE_LABEL, "true");
    }

    @Test
    void handsTheTemplateBackToHaloOnReset() {
        var template = new NotificationTemplate();
        var metadata = new Metadata();
        metadata.setName("template-new-comment-on-post");
        var labels = new java.util.HashMap<String, String>();
        labels.put(ExtensionUtil.DO_NOT_OVERWRITE_LABEL, "true");
        labels.put("halo.run/other", "keep-me");
        metadata.setLabels(labels);
        template.setMetadata(metadata);

        MailTemplateCatalogService.markUserMaintained(template, false);

        assertThat(ExtensionUtil.hasDoNotOverwriteLabel(template)).isFalse();
        assertThat(template.getMetadata().getLabels()).containsEntry("halo.run/other", "keep-me");
    }
}