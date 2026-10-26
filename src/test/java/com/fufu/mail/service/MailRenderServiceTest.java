package com.fufu.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MailRenderServiceTest {

    private final MailRenderService renderService = new MailRenderService(null, null);

    @Test
    void rendersThymeleafInlineExpressions() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("commenter", "小明");
        var result = renderService.render("标题", "[(${commenter})] 评论了你", model);
        assertThat(result).isEqualTo("小明 评论了你");
    }

    @Test
    void rendersSubscriberAndSiteObjects() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("subscriber", Map.of("displayName", "访客"));
        model.put("site", Map.of("title", "fufu 的博客"));
        var result = renderService.render("正文",
            "<p>你好 [(${subscriber.displayName})]，欢迎来到 [(${site.title})]</p>", model);
        assertThat(result).isEqualTo("<p>你好 访客，欢迎来到 fufu 的博客</p>");
    }

    @Test
    void reportsSyntaxErrorsWithThePartName() {
        assertThatThrownBy(() -> renderService.render("HTML 正文", "<p th:text=\"${broken\">", Map.of()))
            .isInstanceOf(MailRenderService.RenderException.class)
            .hasMessageContaining("HTML 正文");
    }

    @Test
    void fillsThePlainTextPartFromHtmlWhenItIsMissing() {
        var rendered = renderService.renderAll("标题", "<p>第一段</p><p>第二段</p>", "", Map.of());
        assertThat(rendered.raw()).isEqualTo("第一段\n第二段");
    }

    @Test
    void keepsAnExplicitPlainTextPart() {
        var rendered = renderService.renderAll("标题", "<p>html</p>", "纯文本版本", Map.of());
        assertThat(rendered.raw()).isEqualTo("纯文本版本");
    }

    @Test
    void stripsMarkupForTheTextAlternative() {
        var text = MailRenderService.toPlainText(
            "<style>p{color:red}</style><h1>标题</h1><p>正文 &amp; 更多</p>");
        assertThat(text).isEqualTo("标题\n正文 & 更多");
    }

    @Test
    void rejectsAnOversizedPart() {
        var huge = "x".repeat(MailRenderService.MAX_PART_LENGTH + 1);
        assertThatThrownBy(() -> renderService.render("正文", huge, Map.of()))
            .isInstanceOf(MailRenderService.RenderException.class)
            .hasMessageContaining("过长");
    }

    @Test
    void treatsMissingPartsAsEmptyText() {
        assertThat(renderService.render("正文", null, Map.of())).isEmpty();
        assertThat(renderService.render("正文", "", Map.of())).isEmpty();
    }
}