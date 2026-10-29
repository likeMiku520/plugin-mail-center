package com.fufu.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fufu.mail.dto.MailDto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LinkApplicationsTest {

    private static Map<String, Object> application(String status) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("url", "https://friend.example.com");
        spec.put("displayName", "朋友的博客");
        spec.put("logo", "https://friend.example.com/logo.png");
        spec.put("description", "记录日常");
        spec.put("email", "friend@example.com");
        spec.put("backlink", "https://friend.example.com/links");
        spec.put("status", status);
        spec.put("origin", Map.of("type", "FORM"));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", "link-application-abc");

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("apiVersion", LinkApplications.API_VERSION);
        root.put("kind", LinkApplications.KIND);
        root.put("metadata", metadata);
        root.put("spec", spec);
        return root;
    }

    @Test
    void readsTheFieldsATemplateNeeds() {
        var view = LinkApplications.toView(application("APPROVED"));

        assertThat(view).isNotNull();
        assertThat(view.name()).isEqualTo("link-application-abc");
        assertThat(view.status()).isEqualTo("APPROVED");
        assertThat(view.displayName()).isEqualTo("朋友的博客");
        assertThat(view.url()).isEqualTo("https://friend.example.com");
        assertThat(view.email()).isEqualTo("friend@example.com");
        assertThat(view.backlink()).isEqualTo("https://friend.example.com/links");
        assertThat(view.originType()).isEqualTo("FORM");
    }

    @Test
    void keepsTheSourceCommentForCommentOriginatedApplications() {
        var data = application("PENDING");
        @SuppressWarnings("unchecked")
        var spec = (Map<String, Object>) data.get("spec");
        spec.put("email", null);
        spec.put("origin", Map.of("type", "COMMENT", "comment", Map.of("name", "comment-1")));

        var view = LinkApplications.toView(data);

        assertThat(view).isNotNull();
        assertThat(view.originType()).isEqualTo("COMMENT");
        assertThat(view.originCommentName()).isEqualTo("comment-1");
        assertThat(view.email()).isNull();
    }

    @Test
    void ignoresExtensionsOfOtherKinds() {
        var other = application("APPROVED");
        other.put("kind", "Link");
        assertThat(LinkApplications.toView(other)).isNull();
        assertThat(LinkApplications.toView(Map.of("kind", "Link"))).isNull();
        assertThat(LinkApplications.toView((Map<String, Object>) null)).isNull();
    }

    @Test
    void treatsOnlyApprovedAndRejectedAsDecided() {
        assertThat(LinkApplications.isDecided("APPROVED")).isTrue();
        assertThat(LinkApplications.isDecided("REJECTED")).isTrue();
        assertThat(LinkApplications.isDecided("PENDING")).isFalse();
        assertThat(LinkApplications.isDecided("APPROVING")).isFalse();
        assertThat(LinkApplications.isDecided(null)).isFalse();
    }

    @Test
    void buildsVariablesWithAHumanReadableResult() {
        var watcher = new LinkApplicationWatcher(null, null, null);
        var approved = watcher.variablesOf(LinkApplications.toView(application("APPROVED")));
        var rejected = watcher.variablesOf(LinkApplications.toView(application("REJECTED")));

        assertThat(approved).containsEntry("displayName", "朋友的博客")
            .containsEntry("reviewResult", "已通过")
            .containsEntry("approved", true);
        assertThat(rejected).containsEntry("reviewResult", "未通过")
            .containsEntry("approved", false);
    }

    @Test
    void sendsToTheApplicantByDefault() {
        var watcher = new LinkApplicationWatcher(null, null, null);
        var spec = new com.fufu.mail.model.MailTrigger.Spec();
        spec.setExtraEmails(List.of("admin@example.com", "FRIEND@example.com"));

        assertThat(watcher.resolveRecipients(spec, "friend@example.com"))
            .containsExactly("friend@example.com", "admin@example.com");
    }

    @Test
    void fixedStrategySkipsTheApplicant() {
        var watcher = new LinkApplicationWatcher(null, null, null);
        var spec = new com.fufu.mail.model.MailTrigger.Spec();
        spec.setRecipientStrategy(com.fufu.mail.model.MailTrigger.RecipientStrategy.FIXED);
        spec.setExtraEmails(List.of("admin@example.com"));

        assertThat(watcher.resolveRecipients(spec, "friend@example.com"))
            .containsExactly("admin@example.com");
    }

    @Test
    void returnsNothingWhenTheApplicantHasNoAddress() {
        var watcher = new LinkApplicationWatcher(null, null, null);
        var spec = new com.fufu.mail.model.MailTrigger.Spec();
        assertThat(watcher.resolveRecipients(spec, null)).isEmpty();
    }

    @Test
    void keepsTheDeclaredAddressWhenItIsValid() {
        var watcher = new LinkApplicationWatcher(null, null, null);
        var view = new MailDto.LinkApplicationView("n", "APPROVED", "d", "u", "l", "x",
            " Friend@Example.com ", "b", "FORM", null);
        assertThat(watcher.applicantEmail(view)).isEqualTo("Friend@Example.com");
    }
}