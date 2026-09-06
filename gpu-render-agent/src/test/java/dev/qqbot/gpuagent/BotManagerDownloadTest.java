package dev.qqbot.gpuagent;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class BotManagerDownloadTest {
    @Test
    void recognizesOfficialQqHttpsAttachmentBeforeWindowsPathParsing() {
        assertTrue(BotManager.isHttpUrl("https://grouptalk.c2c.qq.com/asn.com/qqdownload?fileType=4001&rkey=abc"));
        assertTrue(BotManager.isHttpUrl("HTTP://example.test/file.litematic"));
        assertFalse(BotManager.isHttpUrl("C:\\temp\\building.litematic"));
    }

    @Test
    void downloadsHttpAttachmentAndKeepsSizeLimit() throws Exception {
        byte[] body = "test-litematic".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/file.litematic", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/file.litematic?fileType=4001";
            assertArrayEquals(body, BotManager.downloadHttp(url, 1024));
            assertThrows(java.io.IOException.class, () -> BotManager.downloadHttp(url, 1));
        } finally {
            server.stop(0);
        }
    }
}
