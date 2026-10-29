package com.fufu.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import org.junit.jupiter.api.Test;
import run.halo.app.core.extension.notification.NotificationTemplate;
import run.halo.app.extension.Metadata;

class MailCenterLabelsTest {

    private static NotificationTemplate template() {
        var template = new NotificationTemplate();
        var metadata = new Metadata();
        metadata.setName("template-new-comment-on-post");
        template.setMetadata(metadata);
        return template;
    }

    @Test
    void startsUnhidden() {
        assertThat(MailCenterLabels.isHidden(template())).isFalse();
        assertThat(MailCenterLabels.isAutoProvisioned(template())).isFalse();
    }

    @Test
    void togglesTheHiddenLabel() {
        var template = template();

        MailCenterLabels.setHidden(template, true);
        assertThat(MailCenterLabels.isHidden(template)).isTrue();
        assertThat(template.getMetadata().getLabels())
            .containsEntry(MailCenterLabels.HIDDEN, "true");

        MailCenterLabels.setHidden(template, false);
        assertThat(MailCenterLabels.isHidden(template)).isFalse();
        assertThat(template.getMetadata().getLabels())
            .doesNotContainKey(MailCenterLabels.HIDDEN);
    }

    @Test
    void keepsOtherLabelsWhenClearing() {
        var template = template();
        var labels = new HashMap<String, String>();
        labels.put("halo.run/other", "keep-me");
        template.getMetadata().setLabels(labels);

        MailCenterLabels.setHidden(template, true);
        MailCenterLabels.setHidden(template, false);

        assertThat(template.getMetadata().getLabels()).containsEntry("halo.run/other", "keep-me");
    }

    @Test
    void marksProvisionedContent() {
        var template = template();
        MailCenterLabels.markAutoProvisioned(template);
        assertThat(MailCenterLabels.isAutoProvisioned(template)).isTrue();
    }

    @Test
    void toleratesAnExtensionWithoutMetadata() {
        var template = new NotificationTemplate();
        assertThat(MailCenterLabels.isHidden(template)).isFalse();
        MailCenterLabels.setHidden(template, true);
        assertThat(template.getMetadata()).isNull();
    }
}