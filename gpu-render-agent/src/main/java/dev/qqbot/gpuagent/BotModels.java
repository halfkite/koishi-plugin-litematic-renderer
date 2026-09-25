package dev.qqbot.gpuagent;

import java.util.List;

/** 机器人适配器与渲染管线之间的稳定数据结构。 */
record BotAttachment(String name, String url, String fileId, int busid, long size) {}

record BotMessage(String profileId, String messageId, String userId, String groupId, String selfId,
                  boolean direct, boolean mentioned, List<BotAttachment> attachments,
                  String rawText, java.util.concurrent.atomic.AtomicInteger passiveReplySequence,
                  String quotedMessageId, List<String> messageIndices, List<String> quotedMessageAliases) {
    BotMessage {
        messageIndices = messageIndices == null ? List.of() : List.copyOf(messageIndices);
        java.util.LinkedHashSet<String> aliases = new java.util.LinkedHashSet<>();
        if (quotedMessageId != null && !quotedMessageId.isBlank()) aliases.add(quotedMessageId.trim());
        if (quotedMessageAliases != null) for (String alias : quotedMessageAliases) {
            if (alias != null && !alias.isBlank()) aliases.add(alias.trim());
        }
        quotedMessageAliases = List.copyOf(aliases);
    }

    BotMessage(String profileId, String messageId, String userId, String groupId, String selfId,
               boolean direct, boolean mentioned, List<BotAttachment> attachments, String rawText,
               java.util.concurrent.atomic.AtomicInteger passiveReplySequence,
               String quotedMessageId, List<String> messageIndices) {
        this(profileId, messageId, userId, groupId, selfId, direct, mentioned, attachments, rawText,
                passiveReplySequence, quotedMessageId, messageIndices, List.of());
    }

    BotMessage(String profileId, String messageId, String userId, String groupId, String selfId,
               boolean direct, boolean mentioned, List<BotAttachment> attachments, String rawText) {
        this(profileId, messageId, userId, groupId, selfId, direct, mentioned, attachments, rawText,
                new java.util.concurrent.atomic.AtomicInteger(), "", List.of(), List.of());
    }

    BotMessage(String profileId, String messageId, String userId, String groupId, String selfId,
               boolean direct, boolean mentioned, List<BotAttachment> attachments, String rawText,
               String quotedMessageId) {
        this(profileId, messageId, userId, groupId, selfId, direct, mentioned, attachments, rawText,
                new java.util.concurrent.atomic.AtomicInteger(), quotedMessageId, List.of(), List.of());
    }

    BotMessage(String profileId, String messageId, String userId, String groupId, String selfId,
               boolean direct, boolean mentioned, List<BotAttachment> attachments, String rawText,
               java.util.concurrent.atomic.AtomicInteger passiveReplySequence) {
        this(profileId, messageId, userId, groupId, selfId, direct, mentioned, attachments, rawText,
                passiveReplySequence, "", List.of(), List.of());
    }

    BotMessage(String profileId, String messageId, String userId, String groupId, String selfId,
               boolean direct, boolean mentioned, List<BotAttachment> attachments, String rawText,
               String quotedMessageId, List<String> messageIndices) {
        this(profileId, messageId, userId, groupId, selfId, direct, mentioned, attachments, rawText,
                new java.util.concurrent.atomic.AtomicInteger(), quotedMessageId, messageIndices, List.of());
    }

    int nextPassiveReplySequence() { return passiveReplySequence.incrementAndGet(); }
}

record BotStatus(String profileId, String name, String type, boolean enabled, boolean connected,
                 String state, long reconnects, String lastError, long lastEventAt) {}

record BotAction(String label, String command) {}

record AnnouncementGroup(String profileId, String profileName, String type, String groupId,
                         String groupName, boolean connected) {}

record BotGroup(String id, String name) {}

interface BotAdapter extends AutoCloseable {
    void start();
    BotStatus status();
    void sendResult(BotMessage message, RenderModels.Result result, String metadata);
    void sendResult(BotMessage message, RenderModels.Result result, String metadata, java.nio.file.Path metadataImage);
    default List<String> sendResultTracked(BotMessage message, RenderModels.Result result, String metadata,
                                           java.nio.file.Path metadataImage) {
        sendResult(message, result, metadata, metadataImage);
        return List.of();
    }
    default List<String> sendResultWithActionsTracked(BotMessage message, RenderModels.Result result, String metadata,
                                                      java.nio.file.Path metadataImage, String title,
                                                      List<BotAction> actions) {
        List<String> ids = new java.util.ArrayList<>(sendResultTracked(message, result, metadata, metadataImage));
        if (actions != null && !actions.isEmpty()) ids.addAll(sendActions(message, title, actions));
        return List.copyOf(ids);
    }
    void sendProjection(BotMessage message, RenderModels.Result result, String metadata, java.nio.file.Path projection);
    void sendProjection(BotMessage message, RenderModels.Result result, String metadata,
                        java.nio.file.Path projection, java.nio.file.Path metadataImage);
    void sendSearch(BotMessage message, ProjectionSearch.SearchPage page, java.nio.file.Path contactSheet);
    default List<String> sendSearchTracked(BotMessage message, ProjectionSearch.SearchPage page,
                                           java.nio.file.Path contactSheet) {
        sendSearch(message, page, contactSheet);
        return List.of();
    }
    void sendImage(BotMessage message, java.nio.file.Path image);
    void sendImages(BotMessage message, List<java.nio.file.Path> images);
    void sendFile(BotMessage message, java.nio.file.Path file);
    default List<String> sendFileTracked(BotMessage message, java.nio.file.Path file) {
        sendFile(message, file);
        return List.of();
    }
    default List<String> sendActions(BotMessage message, String title, List<BotAction> actions) {
        return List.of();
    }
    void sendText(BotMessage message, String text);
    default void sendAnnouncement(String profileId, String groupId, String text) {
        sendText(new BotMessage(profileId, "", "", groupId, "", false, false, List.of(), ""), text);
    }
    default List<BotGroup> listGroups() { return List.of(); }
    java.util.concurrent.CompletableFuture<BotAttachment> resolveAttachment(BotMessage message, BotAttachment attachment);
    @Override void close();
}
