package com.fufu.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fufu.mail.model.MailTrigger;
import java.util.List;
import org.junit.jupiter.api.Test;

class TriggerRecipientsTest {

    private static MailTrigger.RecipientRef ref(String email, String name) {
        var reference = new MailTrigger.RecipientRef();
        reference.setEmail(email);
        reference.setName(name);
        return reference;
    }

    @Test
    void eventActorComesFirstThenTheAddressesOnTheRule() {
        var spec = new MailTrigger.Spec();
        spec.setExtraEmails(List.of("admin@example.com"));
        spec.setExtraRecipients(List.of(ref("picked@example.com", "选中的")));

        var result = TriggerRecipients.resolve(spec, "actor@example.com", "author@example.com");

        assertThat(result).containsExactly("actor@example.com", "picked@example.com",
            "admin@example.com");
    }

    @Test
    void postAuthorStrategyUsesTheAuthorOfTheSubject() {
        var spec = new MailTrigger.Spec();
        spec.setRecipientStrategy(MailTrigger.RecipientStrategy.POST_AUTHOR);

        assertThat(TriggerRecipients.resolve(spec, "actor@example.com", "author@example.com"))
            .containsExactly("author@example.com");
    }

    @Test
    void fixedStrategySendsOnlyToTheListOnTheRule() {
        var spec = new MailTrigger.Spec();
        spec.setRecipientStrategy(MailTrigger.RecipientStrategy.FIXED);
        spec.setExtraRecipients(List.of(ref("picked@example.com", null)));

        assertThat(TriggerRecipients.resolve(spec, "actor@example.com", "author@example.com"))
            .containsExactly("picked@example.com");
    }

    @Test
    void theSameMailboxIsQueuedOnceWhateverTheCase() {
        var spec = new MailTrigger.Spec();
        spec.setExtraEmails(List.of("Actor@Example.com"));
        spec.setExtraRecipients(List.of(ref("actor@example.com", null),
            ref("picked@example.com", null), ref("PICKED@example.com", null)));

        var result = TriggerRecipients.resolve(spec, "actor@example.com", null);

        assertThat(result).containsExactly("actor@example.com", "picked@example.com");
    }

    @Test
    void invalidAddressesAreDroppedInsteadOfThrowing() {
        var spec = new MailTrigger.Spec();
        spec.setExtraRecipients(List.of(ref("not-an-email", null), ref("  ok@example.com ", null)));

        assertThat(TriggerRecipients.resolve(spec, null, null)).containsExactly("ok@example.com");
    }

    @Test
    void legacyPlainListStillWorks() {
        var spec = new MailTrigger.Spec();
        spec.setExtraEmails(List.of("old@example.com"));

        assertThat(TriggerRecipients.explicit(spec)).containsExactly("old@example.com");
    }
}