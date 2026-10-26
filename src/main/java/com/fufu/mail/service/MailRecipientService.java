package com.fufu.mail.service;

import com.fufu.mail.dto.MailDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.PageRequestImpl;

/**
 * Builds the address book the console picks recipients from: registered accounts and
 * everyone who ever left a comment with an email address.
 */
@Component
@RequiredArgsConstructor
public class MailRecipientService {

    private static final int COMMENT_SCAN_SIZE = 500;

    private final ExtensionClient client;

    public MailDto.RecipientDirectory directory(String keyword, int limit) {
        var normalized = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        var users = users(normalized);
        var visitors = visitors(normalized);
        var max = Math.max(1, Math.min(limit, 500));
        return new MailDto.RecipientDirectory(
            users.stream().limit(max).toList(),
            visitors.stream().limit(max).toList(),
            users.size(),
            visitors.size());
    }

    public List<MailDto.Recipient> users(String keyword) {
        var matched = new ArrayList<MailDto.Recipient>();
        for (var user : client.listAll(User.class, new ListOptions(), ExtensionUtil.defaultSort())) {
            var spec = user.getSpec();
            if (spec == null || spec.getEmail() == null || spec.getEmail().isBlank()) {
                continue;
            }
            var email = spec.getEmail().trim();
            var displayName = spec.getDisplayName() == null || spec.getDisplayName().isBlank()
                ? email : spec.getDisplayName();
            if (!matches(keyword, email, displayName, user.getMetadata().getName())) {
                continue;
            }
            matched.add(new MailDto.Recipient(email, displayName, spec.getAvatar(), "user",
                spec.isEmailVerified(), null));
        }
        return matched;
    }

    public List<MailDto.Recipient> visitors(String keyword) {
        Map<String, MailDto.Recipient> collected = new LinkedHashMap<>();
        var page = client.listBy(Comment.class, new ListOptions(),
            PageRequestImpl.of(1, COMMENT_SCAN_SIZE, ExtensionUtil.defaultSort()));
        for (var comment : page.getItems()) {
            var spec = comment.getSpec();
            if (spec == null || spec.getOwner() == null) {
                continue;
            }
            var owner = spec.getOwner();
            if (!Comment.CommentOwner.KIND_EMAIL.equalsIgnoreCase(owner.getKind())) {
                continue;
            }
            var email = owner.getName();
            if (email == null || email.isBlank() || !email.contains("@")) {
                continue;
            }
            email = email.trim();
            var displayName = owner.getDisplayName() == null || owner.getDisplayName().isBlank()
                ? email : owner.getDisplayName();
            if (collected.containsKey(email)) {
                continue;
            }
            if (!matches(keyword, email, displayName, null)) {
                continue;
            }
            collected.put(email, new MailDto.Recipient(email, displayName,
                owner.getAnnotation(Comment.CommentOwner.AVATAR_ANNO), "visitor", false,
                comment.getMetadata().getCreationTimestamp() == null
                    ? null : comment.getMetadata().getCreationTimestamp().toString()));
        }
        return new ArrayList<>(collected.values());
    }

    /** Resolves the email of a Halo account, used by triggers that mail a post author. */
    public String emailOfUser(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return client.fetch(User.class, username)
            .map(User::getSpec)
            .map(spec -> spec.getEmail())
            .filter(email -> email != null && !email.isBlank())
            .orElse(null);
    }

    public String displayNameOfUser(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return client.fetch(User.class, username)
            .map(User::getSpec)
            .map(spec -> spec.getDisplayName())
            .orElse(null);
    }

    private static boolean matches(String keyword, String email, String displayName, String name) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        return email.toLowerCase(Locale.ROOT).contains(keyword)
            || displayName.toLowerCase(Locale.ROOT).contains(keyword)
            || name != null && name.toLowerCase(Locale.ROOT).contains(keyword);
    }
}