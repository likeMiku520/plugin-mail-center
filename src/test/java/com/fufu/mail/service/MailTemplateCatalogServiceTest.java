package com.fufu.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

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
}