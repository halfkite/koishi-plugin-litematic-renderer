package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BotManagerAtAllTest {
    @Test
    void suppressesAtAllCommandsAndProjectionAttachmentsBeforeDispatch() throws Exception {
        Path root = Path.of("build", "tmp", "bot-manager-at-all-" + UUID.randomUUID());
        AgentConfig config = new AgentConfig();
        AgentConfig.BotProfile profile = new AgentConfig.BotProfile();
        profile.id = "test";
        profile.enabled = true;
        profile.groupMessageMode = "all";
        config.botProfiles = List.of(profile);

        CountDownLatch commandReply = new CountDownLatch(1);
        CountDownLatch attachmentResolution = new CountDownLatch(1);
        BotAdapter adapter = (BotAdapter) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{BotAdapter.class}, (proxy, method, args) -> {
                    if (method.getName().equals("resolveAttachment")) {
                        BotMessage message = (BotMessage) args[0];
                        if ("all-file".equals(message.messageId())) attachmentResolution.countDown();
                        return CompletableFuture.completedFuture(args[1]);
                    }
                    if (method.getName().equals("sendText") || method.getName().equals("sendImage")) {
                        BotMessage message = (BotMessage) args[0];
                        if ("all-command".equals(message.messageId())) commandReply.countDown();
                    }
                    return null;
                });

        try (BotManager manager = new BotManager(root, root.resolve("agent.json"), config, null, ignored -> {})) {
            var accept = BotManager.class.getDeclaredMethod("accept", BotAdapter.class, BotMessage.class);
            accept.setAccessible(true);
            accept.invoke(manager, adapter, message("all-command", "/发送投影", List.of()));
            accept.invoke(manager, adapter, message("all-file", "群公告正文", List.of(
                    new BotAttachment("machine.litematic", "https://example.test/machine.litematic", "", 0, 0))));

            assertFalse(commandReply.await(1, TimeUnit.SECONDS), "@全体+命令不应发送回复");
            assertFalse(attachmentResolution.await(1, TimeUnit.SECONDS), "@全体+投影附件不应进入文件处理");
        }
    }

    @Test
    void onlyGroupAtAllMessagesAreSuppressed() {
        assertTrue(BotManager.shouldIgnoreAtAllMessage(message("group", "text", List.of())));

        BotMessage direct = new BotMessage("test", "private", "user", "", "bot", true, true,
                List.of(), "@all", new AtomicInteger(), "", List.of(), List.of(), true);
        assertFalse(BotManager.shouldIgnoreAtAllMessage(direct));

        BotMessage ordinaryMention = new BotMessage("test", "member", "user", "group", "bot", false, true,
                List.of(), "@robot /帮助", new AtomicInteger(), "", List.of(), List.of(), false);
        assertFalse(BotManager.shouldIgnoreAtAllMessage(ordinaryMention));
    }

    private static BotMessage message(String id, String text, List<BotAttachment> attachments) {
        return new BotMessage("test", id, "user", "group", "bot", false, true, attachments, text,
                new AtomicInteger(), "", List.of(), List.of(), true);
    }
}
