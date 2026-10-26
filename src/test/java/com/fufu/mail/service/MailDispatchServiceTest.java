package com.fufu.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MailDispatchServiceTest {

    @Test
    void acceptsOrdinaryAddresses() {
        assertThat(MailDispatchService.normalizeEmail("someone@example.com"))
            .isEqualTo("someone@example.com");
        assertThat(MailDispatchService.normalizeEmail("  visitor@mail.example.cn  "))
            .isEqualTo("visitor@mail.example.cn");
        assertThat(MailDispatchService.normalizeEmail("first.last+tag@example.co.uk"))
            .isEqualTo("first.last+tag@example.co.uk");
    }

    @Test
    void rejectsMalformedAddresses() {
        assertThat(MailDispatchService.normalizeEmail(null)).isNull();
        assertThat(MailDispatchService.normalizeEmail("")).isNull();
        assertThat(MailDispatchService.normalizeEmail("no-at-sign")).isNull();
        assertThat(MailDispatchService.normalizeEmail("two@@example.com")).isNull();
        assertThat(MailDispatchService.normalizeEmail("no@domain")).isNull();
        assertThat(MailDispatchService.normalizeEmail("spaces in@example.com")).isNull();
    }

    @Test
    void derivesAStableRecordNameFromTheDedupKey() {
        var first = MailDispatchService.deterministicName("rule|COMMENT_CREATED|c1|a@b.com");
        var second = MailDispatchService.deterministicName("rule|COMMENT_CREATED|c1|a@b.com");
        var other = MailDispatchService.deterministicName("rule|COMMENT_CREATED|c2|a@b.com");

        assertThat(first).isEqualTo(second).matches("[a-z0-9-]{5,60}");
        assertThat(first).isNotEqualTo(other);
    }
}