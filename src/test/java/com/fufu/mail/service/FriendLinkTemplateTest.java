package com.fufu.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fufu.mail.dto.MailDto;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The friend link replies are read in a mail client, where a url without a scheme is
 * resolved against nothing and lands on the wrong page. These tests pin the two halves of
 * the fix: the value is made absolute before it reaches the model, and the anchor text is
 * the same value the href carries.
 */
class FriendLinkTemplateTest {

    private final MailRenderService renderService = new MailRenderService(null, null);

    private final LinkApplicationWatcher watcher =
        new LinkApplicationWatcher(null, null, null);

    private final LinkTemplatesProvisioner provisioner =
        new LinkTemplatesProvisioner(null, null, null, null);

    @Test
    void addsAMissingSchemeToTheApplicantUrl() {
        var view = new MailDto.LinkApplicationView("app", "APPROVED", "小明的博客",
            "xiaoming.example", null, "记录一些日常", "friend@example.com",
            "xiaoming.example/links", "COMMENT", null);

        var variables = watcher.variablesOf(view);

        assertThat(variables.get("websiteUrl")).isEqualTo("https://xiaoming.example");
        assertThat(variables.get("backlink")).isEqualTo("https://xiaoming.example/links");
    }

    @Test
    void keepsAnAbsoluteUrlUntouched() {
        var view = new MailDto.LinkApplicationView("app", "APPROVED", "小明的博客",
            "http://xiaoming.example/", null, null, null, "//xiaoming.example/links", null, null);

        var variables = watcher.variablesOf(view);

        assertThat(variables.get("websiteUrl")).isEqualTo("http://xiaoming.example/");
        assertThat(variables.get("backlink")).isEqualTo("https://xiaoming.example/links");
    }

    @Test
    void approvedMailLinksToTheSameAddressItPrints() {
        var body = provisioner.approvedPayload().content();
        var model = model("xiaoming.example");

        var html = renderService.render("标题", body, model);

        assertThat(html).contains("href=\"https://xiaoming.example\"");
        assertThat(html).contains(">https://xiaoming.example</a>");
        assertThat(html).doesNotContain("href=\"xiaoming.example\"");
    }

    @Test
    void approvedMailPointsTheButtonAtTheSite() {
        var body = provisioner.approvedPayload().content();
        var model = model("xiaoming.example");

        var html = renderService.render("标题", body, model);

        assertThat(html).contains("href=\"https://blog.example.com\"");
        assertThat(html).contains("来看看 fufu 的小窝");
    }

    @Test
    void rejectedMailHidesTheBacklinkRowWhenThereIsNone() {
        var body = provisioner.rejectedPayload().content();
        var model = model("xiaoming.example");

        var html = renderService.render("标题", body, model);

        assertThat(html).doesNotContain("回链页面");
        assertThat(html).contains("href=\"https://xiaoming.example\"");
    }

    private Map<String, Object> model(String applicantUrl) {
        Map<String, Object> model = new HashMap<>();
        model.put("subscriber", Map.of("displayName", "小明", "email", "friend@example.com"));
        model.put("site", Map.of("title", "fufu 的小窝", "url", "https://blog.example.com"));
        model.put("displayName", "小明的博客");
        model.put("websiteUrl", MailRenderService.absoluteUrl(applicantUrl));
        model.put("websiteDescription", "记录一些日常");
        model.put("backlink", null);
        model.put("applicantEmail", "friend@example.com");
        return model;
    }
}