package com.fufu.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SystemTemplatesTest {

    @Test
    void shipsATestMailThatUsesTheSharedVariables() {
        var defaults = SystemTemplates.defaultsOf(SystemTemplates.TEST_KEY);

        assertThat(defaults.subject()).isNotBlank();
        assertThat(defaults.html()).contains("[(${site.title})]");
        assertThat(defaults.raw()).isNotBlank();
    }

    @Test
    void theRecordNameMatchesTheReservedPrefix() {
        assertThat(SystemTemplates.nameOf(SystemTemplates.TEST_KEY))
            .isEqualTo("mail-template-system-test")
            .startsWith(MailTemplateCatalogService.SYSTEM_PREFIX);
    }

    @Test
    void unknownKeysAreRejected() {
        assertThatThrownBy(() -> SystemTemplates.defaultsOf("nope"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nope");
    }

    @Test
    void aStoredBodyIsCutBeforeItCanBloatTheStore() {
        var shortBody = "a".repeat(200);
        var longBody = "b".repeat(200_000);

        assertThat(MailDispatchService.cap(shortBody)).isEqualTo(shortBody);
        assertThat(MailDispatchService.cap(longBody)).hasSizeLessThan(200_000)
            .contains("内容过长");
        assertThat(MailDispatchService.cap(null)).isNull();
    }
}