package com.fufu.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fufu.mail.model.MailTrigger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MailTriggerPollerTest {

    private final MailTriggerPoller poller = new MailTriggerPoller(null, null, null, null, null, null);

    @Test
    void runsWhenNoConditionIsConfigured() {
        assertThat(poller.matchesCondition(null, Map.of())).isTrue();
        assertThat(poller.matchesCondition("  ", Map.of())).isTrue();
    }

    @Test
    void evaluatesConditionsAgainstTheEventVariables() {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("content", "这个东西的价格有点高");
        variables.put("commenter", "小明");

        assertThat(poller.matchesCondition("#content.contains('价格')", variables)).isTrue();
        assertThat(poller.matchesCondition("#commenter == '小明'", variables)).isTrue();
        assertThat(poller.matchesCondition("#content.contains('退款')", variables)).isFalse();
    }

    @Test
    void skipsTheEventWhenAConditionCannotBeEvaluated() {
        Map<String, Object> variables = Map.of("content", "hello");
        assertThat(poller.matchesCondition("#missingField.length() > 0", variables)).isFalse();
        assertThat(poller.matchesCondition("this is not an expression", variables)).isFalse();
    }

    @Test
    void sendsToTheEventActorByDefault() {
        var spec = new MailTrigger.Spec();
        var emails = poller.resolveRecipients(spec, "actor@example.com", "author@example.com");
        assertThat(emails).containsExactly("actor@example.com");
    }

    @Test
    void canTargetThePostAuthorInstead() {
        var spec = new MailTrigger.Spec();
        spec.setRecipientStrategy(MailTrigger.RecipientStrategy.POST_AUTHOR);
        var emails = poller.resolveRecipients(spec, "actor@example.com", "author@example.com");
        assertThat(emails).containsExactly("author@example.com");
    }

    @Test
    void fixedStrategyUsesOnlyTheExplicitList() {
        var spec = new MailTrigger.Spec();
        spec.setRecipientStrategy(MailTrigger.RecipientStrategy.FIXED);
        spec.setExtraEmails(List.of("admin@example.com", "not-an-email"));
        var emails = poller.resolveRecipients(spec, "actor@example.com", "author@example.com");
        assertThat(emails).containsExactly("admin@example.com");
    }

    @Test
    void extraRecipientsAreAddedToTheStrategyResult() {
        var spec = new MailTrigger.Spec();
        spec.setRecipientStrategy(MailTrigger.RecipientStrategy.EVENT_ACTOR);
        spec.setExtraEmails(List.of("admin@example.com", "ACTOR@example.com"));
        var emails = poller.resolveRecipients(spec, "actor@example.com", null);
        assertThat(emails).containsExactly("actor@example.com", "admin@example.com");
    }

    @Test
    void producesAnEmptyListWhenNothingCanBeResolved() {
        var spec = new MailTrigger.Spec();
        assertThat(poller.resolveRecipients(spec, null, null)).isEmpty();
    }

    @Test
    void rejectsUnknownEventTypes() {
        assertThatThrownBy(() -> MailTriggerService.parseEventType("SOMETHING_ELSE"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不支持的触发事件");
    }
}