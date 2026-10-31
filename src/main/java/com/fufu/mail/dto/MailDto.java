package com.fufu.mail.dto;

import java.util.List;
import java.util.Map;

/**
 * Wire format shared by the console UI and the trigger API. Every member is a record so
 * the console can serialize a request body straight into one of these without a mapping
 * layer.
 */
public final class MailDto {

    private MailDto() {
    }

    /** A value a template can use, described well enough to render an input for it. */
    public record TemplateVariable(String name, String label, String type, String sampleValue,
                                   boolean required) {
    }

    /** A Halo notification template. Editable, but Halo owns the record. */
    public record BuiltinTemplate(String name, String reasonType, String language, String title,
                                  String htmlBody, String rawBody, String createdAt, boolean hidden,
                                  boolean edited) {
    }

    /** All templates Halo can send for one reason, with the attributes they receive. */
    public record BuiltinGroup(String reasonType, String displayName, String description,
                               boolean hidden, List<TemplateVariable> variables,
                               List<BuiltinTemplate> templates) {
    }

    /** A template authored in this plugin. */
    public record CustomTemplate(String name, String displayName, String description,
                                 String subject, String content, String rawContent,
                                 List<TemplateVariable> variables, boolean enabled,
                                 String createdAt, boolean hidden) {
    }

    /** A template the plugin itself ships, editable but not deletable. */
    public record SystemTemplate(String key, String name, String displayName, String description,
                                 String subject, String htmlBody, String rawBody,
                                 List<TemplateVariable> variables, boolean edited) {
    }

    /** One address a mail can be sent to, wherever it came from. */
    public record Recipient(String email, String displayName, String avatar, String kind,
                            boolean verified, String lastSeenAt) {
    }

    public record RecipientDirectory(List<Recipient> users, List<Recipient> visitors,
                                     int totalUsers, int totalVisitors) {
    }

    public record SendTarget(String email, String name) {
    }

    public record SendResult(String email, String name, boolean success, String error,
                             String logName) {
    }

    public record SendSummary(int total, int success, int failed, List<SendResult> results) {
    }

    public record TemplatePreview(String subject, String html, String raw,
                                  List<TemplateVariable> variables) {
    }

    public record LogView(String name, String recipient, String recipientName, String templateType,
                          String templateName, String templateDisplayName, String subject,
                          String status, String error, String source, String triggerName,
                          String sentAt, Long durationMs) {
    }

    /** Full record of one send, including the body that actually went out. */
    public record LogDetail(String name, String recipient, String recipientName, String templateType,
                            String templateName, String templateDisplayName, String subject,
                            String status, String error, String source, String triggerName,
                            String dedupKey, String sentAt, Long durationMs, String htmlBody,
                            String rawBody) {
    }

    public record LogPage(List<LogView> items, long total, int page, int size) {
    }

    public record TriggerView(String name, String displayName, boolean enabled, String eventType,
                              String templateType, String templateName, String templateDisplayName,
                              String subjectPreview, String recipientStrategy,
                              List<String> extraEmails, String condition, String startedAt,
                              Long sent, Long failed, Long skipped, String lastRunAt,
                              String lastMessage, List<SendTarget> extraRecipients) {
    }

    public record Overview(int builtinTemplates, int builtinReasonTypes, int customTemplates,
                           int triggers, int enabledTriggers, long sentTotal, long failedTotal,
                           long sentToday, String channelSource, boolean channelUsable,
                           String channelProblem, boolean apiEnabled, String apiKey,
                           boolean linksPluginDetected) {
    }

    /** Channel configuration with the stored password replaced by a presence flag. */
    public record ChannelView(String source, String notifierDescriptor, String host, Integer port,
                              String username, boolean hasPassword, String sender,
                              String displayName, String encryption, boolean apiEnabled,
                              String apiKey, String resolvedSource, boolean usable,
                              String problem) {
    }

    public record ApiResponse(boolean ok, String message) {
    }

    public record CustomTemplatePayload(String name, String displayName, String description,
                                        String subject, String content, String rawContent,
                                        List<TemplateVariable> variables, Boolean enabled) {
    }

    /** Only the body of a system template is editable; its name belongs to Halo. */
    public record BuiltinTemplatePayload(String title, String htmlBody, String rawBody) {
    }

    /** Body of one of the plugin's own templates, which share the custom shape. */
    public record SystemTemplatePayload(String subject, String htmlBody, String rawBody) {
    }

    public record HiddenPayload(Boolean hidden) {
    }

    public record ValidatePayload(String subject, String content, String rawContent,
                                  List<TemplateVariable> variables) {
    }

    public record PreviewPayload(String templateType, String templateName,
                                 Map<String, Object> variables) {
    }

    public record SendPayload(String templateType, String templateName, List<SendTarget> targets,
                              Map<String, Object> variables, String subjectOverride) {
    }

    public record TriggerPayload(String name, String displayName, Boolean enabled, String eventType,
                                 String templateType, String templateName,
                                 String recipientStrategy, List<String> extraEmails,
                                 String condition, List<SendTarget> extraRecipients) {
    }

    public record ChannelPayload(String source, String notifierDescriptor, String host, Integer port,
                                 String username, String password, String sender,
                                 String displayName, String encryption, Boolean apiEnabled,
                                 Integer rateLimitPerMinute, Boolean regenerateApiKey) {
    }

    public record TestMailPayload(String to) {
    }

    /** Body accepted by the key protected trigger endpoint. */
    public record OpenTriggerPayload(String template, List<String> to, Map<String, Object> variables,
                                     String subject) {
    }

    /** What the links plugin tells us about one application, parsed from its extension. */
    public record LinkApplicationView(String name, String status, String displayName, String url,
                                      String logo, String description, String email,
                                      String backlink, String originType, String originCommentName) {
    }

    // ---------------------------------------------------------------------------------
    // Template bundle: the file a user exports, edits outside Halo and imports back.
    // ---------------------------------------------------------------------------------

    /** Values every template receives regardless of the event. */
    public record BundleGlobals(List<TemplateVariable> variables, String hint) {
    }

    public record BundleBuiltin(String name, String reasonType, String reasonDisplayName,
                                String language, String title, String htmlBody, String rawBody,
                                List<TemplateVariable> variables) {
    }

    public record BundleCustom(String name, String displayName, String description, String subject,
                               String htmlBody, String rawBody, boolean enabled, boolean hidden,
                               List<TemplateVariable> variables) {
    }

    public record BundleSystem(String key, String name, String displayName, String description,
                               String subject, String htmlBody, String rawBody,
                               List<TemplateVariable> variables) {
    }

    /** The exported document. Field order is the reading order for an editor. */
    public record TemplateBundle(String format, int formatVersion, String exportedAt, String siteTitle,
                                 String siteUrl, List<String> notes, BundleGlobals globals,
                                 List<BundleBuiltin> builtin, List<BundleCustom> custom,
                                 List<BundleSystem> system) {
    }

    public record BundleImportItem(String section, String name, String displayName, String status,
                                   String message) {
    }

    public record BundleImportResult(boolean dryRun, int total, int updated, int unchanged,
                                    int skipped, int failed,
                                    List<BundleImportItem> items) {
    }
}