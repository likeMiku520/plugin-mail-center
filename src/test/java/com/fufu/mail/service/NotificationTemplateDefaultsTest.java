package com.fufu.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class NotificationTemplateDefaultsTest {

    private static final String YAML = """
        apiVersion: notification.halo.run/v1alpha1
        kind: ReasonType
        metadata:
          name: new-comment-on-post
        ---
        apiVersion: notification.halo.run/v1alpha1
        kind: NotificationTemplate
        metadata:
          name: template-new-comment-on-post
        spec:
          reasonSelector:
            reasonType: new-comment-on-post
            language: default
          template:
            title: "[(${commenter})] 评论了你的文章"
            rawBody: |
              纯文本
            htmlBody: |
              <p>HTML</p>
        """;

    private static ByteArrayInputStream stream() {
        return new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void keepsOnlyNotificationTemplates() {
        var parsed = NotificationTemplateDefaults.parse(stream());

        assertThat(parsed).containsOnlyKeys("template-new-comment-on-post");
    }

    @Test
    void readsTheThreeEditableParts() {
        var parsed = NotificationTemplateDefaults.parse(stream());
        var template = parsed.get("template-new-comment-on-post");

        assertThat(template.title()).isEqualTo("[(${commenter})] 评论了你的文章");
        assertThat(template.htmlBody()).contains("<p>HTML</p>");
        assertThat(template.rawBody()).contains("纯文本");
        assertThat(template.reasonType()).isEqualTo("new-comment-on-post");
        assertThat(template.language()).isEqualTo("default");
    }

    @Test
    void toleratesDocumentsWithoutATemplateBody() {
        var yaml = """
            kind: NotificationTemplate
            metadata:
              name: broken
            spec: {}
            """;
        var parsed = NotificationTemplateDefaults.parse(
            new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        assertThat(parsed).isEmpty();
    }

    @Test
    void returnsNullWhenTheResourceIsNotOnTheClassPath() {
        var defaults = new NotificationTemplateDefaults();
        assertThat(defaults.find("template-new-comment-on-post")).isNull();
    }
}