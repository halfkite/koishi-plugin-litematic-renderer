package dev.qqbot.gpuagent;

import java.util.List;

/** 机器人适配器与渲染管线之间的稳定数据结构。 */
record BotAttachment(String name, String url, String fileId, int busid, long size) {}

record BotMessage(String profileId, String messageId, String userId, String groupId, String selfId,
                  boolean direct, boolean mentioned, List<BotAttachment> attachments,
                  String rawText) {}

record BotStatus(String profileId, String name, String type, boolean enabled, boolean connected,
                 String state, long reconnects, String lastError, long lastEventAt) {}

interface BotAdapter extends AutoCloseable {
    void start();
    BotStatus status();
    void sendResult(BotMessage message, RenderModels.Result result, String metadata);
    void sendText(BotMessage message, String text);
    java.util.concurrent.CompletableFuture<BotAttachment> resolveAttachment(BotMessage message, BotAttachment attachment);
    @Override void close();
}
