package com.fufu.mail;

/**
 * Values of the plugin's own Setting form, group {@code basic}. Everything mail
 * related lives in {@link MailChannel} instead, because SMTP credentials must not be
 * written to a ConfigMap.
 */
public record MailCenterSettings(
    Integer logRetentionDays,
    Integer maxLogs,
    Integer triggerIntervalSeconds,
    Boolean notifyOnlyApprovedComments,
    Integer triggerLookbackHours,
    Integer sendRateLimitPerMinute) {

    public static final String GROUP = "basic";

    public static MailCenterSettings empty() {
        return new MailCenterSettings(null, null, null, null, null, null);
    }

    public int logRetentionDaysOrDefault() {
        return logRetentionDays == null ? 90 : Math.max(0, Math.min(logRetentionDays, 3650));
    }

    public int maxLogsOrDefault() {
        return maxLogs == null ? 2000 : Math.max(100, Math.min(maxLogs, 100_000));
    }

    public int triggerIntervalSecondsOrDefault() {
        return triggerIntervalSeconds == null ? 60 : Math.max(15, Math.min(triggerIntervalSeconds, 3600));
    }

    public boolean notifyOnlyApprovedCommentsOrDefault() {
        return notifyOnlyApprovedComments == null || notifyOnlyApprovedComments;
    }

    public int triggerLookbackHoursOrDefault() {
        return triggerLookbackHours == null ? 24 : Math.max(1, Math.min(triggerLookbackHours, 720));
    }

    public int sendRateLimitPerMinuteOrDefault() {
        return sendRateLimitPerMinute == null ? 60 : Math.max(1, Math.min(sendRateLimitPerMinute, 6000));
    }
}