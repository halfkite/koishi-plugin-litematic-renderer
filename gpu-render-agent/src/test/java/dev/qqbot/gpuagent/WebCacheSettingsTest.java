package dev.qqbot.gpuagent;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class WebCacheSettingsTest {
    @TempDir Path root;

    @Test void cacheThresholdsAreVisiblePersistedAndAppliedWithoutRestart() throws Exception {
        Path path = root.resolve("agent.json");
        AgentConfig config = AgentConfig.load(path);
        config.webEnabled = true; config.webBindHost = "127.0.0.1"; config.webPort = 0;
        try (var renderer = new RenderService(root, config);
             var bots = new BotManager(root, path, config, renderer, ignored -> {});
             var cloud = new CloudConnection(config, renderer, ignored -> {});
             var web = new WebAdminServer(root, path, config, renderer, bots, cloud, ignored -> {});
             var client = HttpClient.newHttpClient()) {
            web.start();
            var field = WebAdminServer.class.getDeclaredField("server"); field.setAccessible(true);
            config.webPort = ((HttpServer) field.get(web)).getAddress().getPort();
            String base = "http://127.0.0.1:" + config.webPort;
            var loginBody = new com.google.gson.JsonObject();
            loginBody.addProperty("username", config.webUsername); loginBody.addProperty("password", config.webPassword);
            var login = client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                    .POST(HttpRequest.BodyPublishers.ofString(loginBody.toString())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, login.statusCode());
            String cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            var read = client.send(HttpRequest.newBuilder(URI.create(base + "/api/config")).header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(read.body().contains("cacheImageMinProjectionBytes"));
            assertTrue(read.body().contains("cacheImageMinCalls"));
            var saved = client.send(HttpRequest.newBuilder(URI.create(base + "/api/config")).header("Cookie", cookie)
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"cacheImageMinProjectionBytes\":4194304,\"cacheImageMinCalls\":50}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, saved.statusCode(), saved.body());
            assertFalse(Protocol.GSON.fromJson(saved.body(), com.google.gson.JsonObject.class).get("restartRequired").getAsBoolean());
            assertEquals(4194304, config.cacheImageMinProjectionBytes);
            assertEquals(50, AgentConfig.load(path).cacheImageMinCalls);
            var invalid = client.send(HttpRequest.newBuilder(URI.create(base + "/api/config")).header("Cookie", cookie)
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"cacheImageMinCalls\":-1}")).build(), HttpResponse.BodyHandlers.ofString());
            assertNotEquals(200, invalid.statusCode());
            assertEquals(50, config.cacheImageMinCalls);
            var page = client.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(page.body().contains("id=\"cacheImageMb\""));
            assertTrue(page.body().contains("id=\"cacheImageCalls\""));
            assertTrue(page.body().contains("id=\"renderTimeoutSeconds\""));
            assertTrue(page.body().contains("id=\"userRenderLimitEnabled\""));
            var limits = client.send(HttpRequest.newBuilder(URI.create(base + "/api/config")).header("Cookie", cookie)
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"renderTimeoutMillis\":12000,\"userRenderLimitEnabled\":true,\"userRenderWindowSeconds\":30,\"userRenderMaxFiles\":2}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, limits.statusCode(), limits.body());
            assertFalse(Protocol.GSON.fromJson(limits.body(), com.google.gson.JsonObject.class).get("restartRequired").getAsBoolean());
            assertEquals(12000, config.renderTimeoutMillis);
            assertEquals(30, config.userRenderWindowSeconds);
            assertEquals(2, AgentConfig.load(path).userRenderMaxFiles);
            for (String invalidBody : java.util.List.of("{\"renderTimeoutMillis\":0}", "{\"userRenderWindowSeconds\":0}", "{\"userRenderMaxFiles\":0}")) {
                var rejected = client.send(HttpRequest.newBuilder(URI.create(base + "/api/config")).header("Cookie", cookie)
                        .PUT(HttpRequest.BodyPublishers.ofString(invalidBody)).build(), HttpResponse.BodyHandlers.ofString());
                assertNotEquals(200, rejected.statusCode());
                assertEquals(12000, config.renderTimeoutMillis);
                assertEquals(30, config.userRenderWindowSeconds);
                assertEquals(2, config.userRenderMaxFiles);
            }
        }
    }
}
