package com.fufu.mail.service;

import com.fufu.mail.MailCenterSettings;
import com.fufu.mail.dto.MailDto;
import com.fufu.mail.model.MailSendLog;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ListOptions;

/**
 * The send history: paging for the console, counters for the overview and the retention
 * worker that keeps the record count bounded.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailLogService {

    private static final int MAX_PAGE_SIZE = 200;

    private final ExtensionClient client;
    private final MailTriggerPoller poller;

    private ScheduledExecutorService scheduler;

    public void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "mail-center-retention");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::cleanupSafely, 60, Duration.ofHours(6).toSeconds(),
            TimeUnit.SECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    void cleanupSafely() {
        try {
            var settings = poller.settings();
            cleanup(settings.logRetentionDaysOrDefault(), settings.maxLogsOrDefault());
        } catch (Exception e) {
            log.debug("Mail record cleanup failed", e);
        }
    }

    public List<MailSendLog> all() {
        return client.listAll(MailSendLog.class, new ListOptions(), ExtensionUtil.defaultSort());
    }

    public MailDto.LogPage page(int page, int size, String status, String keyword) {
        var pageNumber = Math.max(1, page);
        var pageSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        var normalizedStatus = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        var normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);

        var filtered = all().stream()
            .filter(record -> {
                var spec = record.getSpec();
                if (spec == null) {
                    return false;
                }
                if (normalizedStatus != null && !normalizedStatus.equalsIgnoreCase(spec.getStatus())) {
                    return false;
                }
                if (normalizedKeyword.isEmpty()) {
                    return true;
                }
                return contains(spec.getRecipient(), normalizedKeyword)
                    || contains(spec.getRecipientName(), normalizedKeyword)
                    || contains(spec.getSubject(), normalizedKeyword)
                    || contains(spec.getTemplateDisplayName(), normalizedKeyword);
            })
            .toList();

        var from = Math.min((pageNumber - 1) * pageSize, filtered.size());
        var to = Math.min(from + pageSize, filtered.size());
        var items = filtered.subList(from, to).stream().map(this::toView).toList();
        return new MailDto.LogPage(items, filtered.size(), pageNumber, pageSize);
    }

    public int clear() {
        var records = all();
        records.forEach(client::delete);
        return records.size();
    }

    /** Drops records past the retention window and then trims the oldest overflow. */
    public int cleanup(int retentionDays, int maxLogs) {
        var records = new ArrayList<>(all());
        var removed = new LinkedHashSet<String>();
        if (retentionDays > 0) {
            var cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
            for (var record : records) {
                var spec = record.getSpec();
                var sentAt = spec == null ? null : spec.getSentAt();
                var createdAt = sentAt != null ? sentAt : record.getMetadata().getCreationTimestamp();
                if (createdAt != null && createdAt.isBefore(cutoff)) {
                    client.delete(record);
                    removed.add(record.getMetadata().getName());
                }
            }
        }
        if (maxLogs > 0) {
            var survivors = records.stream()
                .filter(record -> !removed.contains(record.getMetadata().getName()))
                .toList();
            var overflow = survivors.size() - maxLogs;
            for (var index = 0; index < overflow; index++) {
                // The listing is newest first, so the tail holds the oldest records.
                client.delete(survivors.get(survivors.size() - 1 - index));
                removed.add(survivors.get(survivors.size() - 1 - index).getMetadata().getName());
            }
        }
        if (!removed.isEmpty()) {
            log.info("Removed {} mail records past retention", removed.size());
        }
        return removed.size();
    }

    /** One pass over the history for every counter the overview shows. */
    public Counters counters() {
        var start = LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant();
        long sent = 0;
        long failed = 0;
        long sentToday = 0;
        for (var record : all()) {
            var spec = record.getSpec();
            if (spec == null) {
                continue;
            }
            var success = "SUCCESS".equalsIgnoreCase(spec.getStatus());
            if (success) {
                sent++;
            } else if ("FAILED".equalsIgnoreCase(spec.getStatus())) {
                failed++;
            }
            var sentAt = spec.getSentAt();
            if (success && sentAt != null && !sentAt.isBefore(start)) {
                sentToday++;
            }
        }
        return new Counters(sent, failed, sentToday);
    }

    public record Counters(long sent, long failed, long sentToday) {
    }

    private MailDto.LogView toView(MailSendLog record) {
        var spec = record.getSpec();
        return new MailDto.LogView(
            record.getMetadata().getName(),
            spec.getRecipient(),
            spec.getRecipientName(),
            spec.getTemplateType(),
            spec.getTemplateName(),
            spec.getTemplateDisplayName(),
            spec.getSubject(),
            spec.getStatus(),
            spec.getError(),
            spec.getSource(),
            spec.getTriggerName(),
            spec.getSentAt() == null ? null : spec.getSentAt().toString(),
            spec.getDurationMs());
    }

    private static boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }
}