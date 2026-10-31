package com.fufu.mail.service;

import com.fufu.mail.model.MailTrigger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Works out who a rule should answer. The address implied by the event comes first, then
 * the addresses attached to the rule itself, which can be picked from the site's users,
 * its visitors, or typed by hand. Addresses are de-duplicated by their lower cased form so
 * the same mailbox is never queued twice.
 */
final class TriggerRecipients {

    private TriggerRecipients() {
    }

    static Set<String> resolve(MailTrigger.Spec spec, String actorEmail, String postOwnerEmail) {
        Map<String, String> unique = new LinkedHashMap<>();
        var strategy = spec.getRecipientStrategy() == null
            ? MailTrigger.RecipientStrategy.EVENT_ACTOR : spec.getRecipientStrategy();
        switch (strategy) {
            case EVENT_ACTOR -> add(unique, actorEmail);
            case POST_AUTHOR -> add(unique, postOwnerEmail);
            case FIXED -> {
                // Only the addresses attached to the rule are used.
            }
        }
        explicit(spec).forEach(email -> add(unique, email));
        return new java.util.LinkedHashSet<>(unique.values());
    }

    /** Addresses stored on the rule, newest shape first then the legacy plain list. */
    static List<String> explicit(MailTrigger.Spec spec) {
        Map<String, String> unique = new LinkedHashMap<>();
        if (spec.getExtraRecipients() != null) {
            spec.getExtraRecipients().forEach(recipient -> add(unique, recipient.getEmail()));
        }
        if (spec.getExtraEmails() != null) {
            spec.getExtraEmails().forEach(email -> add(unique, email));
        }
        return List.copyOf(unique.values());
    }

    static void add(Map<String, String> unique, String email) {
        var normalized = MailDispatchService.normalizeEmail(email);
        if (normalized != null) {
            unique.putIfAbsent(normalized.toLowerCase(Locale.ROOT), normalized);
        }
    }
}