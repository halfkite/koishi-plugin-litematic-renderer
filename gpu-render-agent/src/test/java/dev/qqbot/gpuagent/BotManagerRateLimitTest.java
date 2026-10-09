package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BotManagerRateLimitTest {
    @TempDir Path root;

    @Test void excessAttachmentsAreRejectedBeforeResolutionAcrossConversations() throws Exception {
        var config = new AgentConfig();
        var profile = new AgentConfig.BotProfile();
        profile.id = "test"; profile.enabled = true; profile.allowPrivateRender = true;
        profile.groupMessageMode = "all";
        config.botProfiles = List.of(profile);
        var resolved = new AtomicInteger();
        var notices = new ArrayList<String>();
        BotAdapter adapter = (BotAdapter) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{BotAdapter.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("resolveAttachment")) {
                        resolved.incrementAndGet();
                        return CompletableFuture.failedFuture(new IOException("test attachment resolution failed"));
                    }
                    if (method.getName().equals("sendText")) notices.add((String) args[1]);
                    return null;
                });
        try (var renderer = new RenderService(root, config);
             var bots = new BotManager(root, root.resolve("agent.json"), config, renderer, ignored -> {})) {
            var process = BotManager.class.getDeclaredMethod("process", BotAdapter.class, BotMessage.class);
            process.setAccessible(true);
            List<BotAttachment> fourFiles = java.util.stream.IntStream.range(0, 4)
                    .mapToObj(i -> new BotAttachment("file" + i + ".litematic", "https://example.test/" + i, "", 0, 1)).toList();
            process.invoke(bots, adapter, new BotMessage("test", "m1", "user", "group1", "bot", false, true, fourFiles, ""));
            assertEquals(3, resolved.get());
            assertTrue(notices.getLast().contains("单人 CD"));
            assertTrue(notices.getLast().contains("不下载、不进入渲染队列"));
            process.invoke(bots, adapter, new BotMessage("test", "m2", "user", "group2", "bot", false, true, fourFiles.subList(0, 1), ""));
            process.invoke(bots, adapter, new BotMessage("test", "m3", "user", "", "bot", true, true, fourFiles.subList(0, 1), ""));
            assertEquals(3, resolved.get());
            process.invoke(bots, adapter, new BotMessage("test", "m4", "other", "group1", "bot", false, true, fourFiles.subList(0, 1), ""));
            assertEquals(4, resolved.get());
            assertEquals(0, renderer.queueLength());
        }
    }
}
