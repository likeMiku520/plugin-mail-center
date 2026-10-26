package com.fufu.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fufu.mail.model.MailTrigger;
import com.fufu.mail.model.MailTrigger.EventType;
import com.fufu.mail.model.MailTrigger.RecipientStrategy;
import com.fufu.mail.model.MailTrigger.TemplateType;
import org.junit.jupiter.api.Test;

class MailTriggerServiceTest {

    @Test
    void parsesEventTypesCaseInsensitively() {
        assertThat(MailTriggerService.parseEventType("comment_created"))
            .isEqualTo(EventType.COMMENT_CREATED);
        assertThat(MailTriggerService.parseEventType(" user_registered "))
            .isEqualTo(EventType.USER_REGISTERED);
        assertThat(MailTriggerService.parseEventType(null)).isEqualTo(EventType.COMMENT_CREATED);
    }

    @Test
    void parsesTemplateTypes() {
        assertThat(MailTriggerService.parseTemplateType("custom")).isEqualTo(TemplateType.CUSTOM);
        assertThat(MailTriggerService.parseTemplateType(null)).isEqualTo(TemplateType.BUILTIN);
        assertThatThrownBy(() -> MailTriggerService.parseTemplateType("fancy"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesRecipientStrategies() {
        assertThat(MailTriggerService.parseStrategy("post_author"))
            .isEqualTo(RecipientStrategy.POST_AUTHOR);
        assertThat(MailTriggerService.parseStrategy(null))
            .isEqualTo(RecipientStrategy.EVENT_ACTOR);
        assertThatThrownBy(() -> MailTriggerService.parseStrategy("everyone"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不支持的收件人策略");
    }
}