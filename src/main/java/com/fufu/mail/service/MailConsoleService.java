package com.fufu.mail.service;

import com.fufu.mail.MailCenterSettings;
import com.fufu.mail.MailChannel;
import com.fufu.mail.MailChannelStore;
import com.fufu.mail.dto.MailDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Assembles the values the console landing page shows: how many templates exist, what
 * the last sends did and whether the mail account can actually deliver.
 */
@Component
@RequiredArgsConstructor
public class MailConsoleService {

    private final MailTemplateCatalogService catalog;
    private final MailTriggerService triggerService;
    private final MailLogService logService;
    private final MailChannelStore channelStore;
    private final MailTriggerPoller poller;
    private final LinkTemplatesProvisioner linkProvisioner;

    public MailDto.Overview overview() {
        var groups = catalog.listBuiltinGroups();
        var custom = catalog.listCustom();
        var triggers = triggerService.listViews();
        var resolved = channelStore.resolve();
        var channel = channelStore.load();
        var counters = logService.counters();
        return new MailDto.Overview(
            groups.stream().mapToInt(group -> group.templates().size()).sum(),
            groups.size(),
            custom.size(),
            triggers.size(),
            (int) triggers.stream().filter(MailDto.TriggerView::enabled).count(),
            counters.sent(),
            counters.failed(),
            counters.sentToday(),
            resolved.source(),
            resolved.usable(),
            resolved.problem(),
            channel.apiEnabledOrDefault(),
            channel.apiKey(),
            linkProvisioner.linksPluginPresent());
    }

    /**
     * Reads the channel for display. Deliberately read only: the trigger endpoint is
     * reachable without a session, and a read path there must never write storage.
     */
    public MailDto.ChannelView channelView() {
        var channel = channelStore.load();
        var resolved = channelStore.resolve();
        return new MailDto.ChannelView(
            channel.sourceOrDefault(),
            channel.notifierDescriptorOrDefault(),
            channel.host(),
            channel.port(),
            channel.username(),
            channel.password() != null && !channel.password().isBlank(),
            channel.sender(),
            channel.displayName(),
            channel.encryptionOrDefault(),
            channel.apiEnabledOrDefault(),
            channel.apiKey(),
            resolved.source(),
            resolved.usable(),
            resolved.problem());
    }

    public MailDto.ChannelView saveChannel(MailDto.ChannelPayload payload) {
        var current = channelStore.loadOrCreateWithApiKey();
        var source = MailChannel.SOURCE_CUSTOM.equalsIgnoreCase(payload.source())
            ? MailChannel.SOURCE_CUSTOM : MailChannel.SOURCE_HALO;
        var password = payload.password() == null || payload.password().isEmpty()
            ? current.password() : payload.password();
        var apiKey = Boolean.TRUE.equals(payload.regenerateApiKey()) || current.apiKey() == null
            || current.apiKey().isBlank()
            ? MailChannel.newApiKey() : current.apiKey();
        var updated = new MailChannel(
            source,
            blankToNull(payload.notifierDescriptor()) == null
                ? current.notifierDescriptorOrDefault() : payload.notifierDescriptor().trim(),
            blankToNull(payload.host()),
            payload.port(),
            blankToNull(payload.username()),
            password,
            blankToNull(payload.sender()),
            blankToNull(payload.displayName()),
            payload.encryption() == null ? current.encryptionOrDefault() : payload.encryption(),
            payload.apiEnabled() == null ? current.apiEnabled() : payload.apiEnabled(),
            apiKey,
            payload.rateLimitPerMinute() == null ? current.rateLimitPerMinute() : payload.rateLimitPerMinute());
        channelStore.save(updated);
        return channelView();
    }

    /** Effective settings, so the console can display the same numbers the worker uses. */
    public MailCenterSettings settings() {
        return poller.settings();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}