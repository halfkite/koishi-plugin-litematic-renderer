package dev.qqbot.gpuagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class HttpV1Server implements AutoCloseable {
    private final AgentConfig config;
    private final RenderService renderer;
    private final Map<String, Long> nonces = new ConcurrentHashMap<>();
    private final Consumer<String> log;
    private HttpServer server;

    HttpV1Server(AgentConfig config, RenderService renderer, Consumer<String> log) {
        this.config = config;
        this.renderer = renderer;
        this.log = log;
    }

    void start() throws IOException {
        if (server != null || config.sharedSecret == null || config.sharedSecret.isBlank()) return;
        server = HttpServer.create(new InetSocketAddress(config.listenHost, config.listenPort), 16);
        server.createContext("/v1/render", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        log.accept("HTTP v1 已监听 http://" + config.listenHost + ":" + config.listenPort + "/v1/render");
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) { reply(exchange, 404, error("not found")); return; }
        try {
            byte[] body = exchange.getRequestBody().readNBytes(Math.toIntExact(Math.min(Integer.MAX_VALUE, config.maxRequestBytes + 1)));
            if (body.length > config.maxRequestBytes) throw new SecurityException("request body exceeds configured limit");
            String timestamp = exchange.getRequestHeaders().getFirst("X-Litematic-Agent-Timestamp");
            String nonce = exchange.getRequestHeaders().getFirst("X-Litematic-Agent-Nonce");
            String supplied = exchange.getRequestHeaders().getFirst("X-Litematic-Agent-Signature");
            long time = Long.parseLong(timestamp == null ? "" : timestamp);
            if (Math.abs(System.currentTimeMillis() - time) > config.maxClockSkewSeconds * 1000L) throw new SecurityException("request timestamp is outside allowed clock skew");
            String signed = timestamp + "." + nonce + "." + new String(body, StandardCharsets.UTF_8);
            if (!Protocol.secureHexEquals(supplied, Protocol.hmac(config.sharedSecret, signed))) throw new SecurityException("invalid request signature");
            consumeNonce(nonce);
            JsonObject payload = Protocol.GSON.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
            if (payload.get("version").getAsInt() != 1) throw new IllegalArgumentException("invalid render payload version");
            byte[] schematic = Base64.getDecoder().decode(payload.get("litematicBase64").getAsString());
            JsonObject options = payload.getAsJsonObject("options");
            int size = integer(options, "outputSize", 1024);
            // 分辨率由本地工具接管：云端下发的 outputSize 仅作缺省
            int width = config.renderWidth > 0 ? config.renderWidth : size;
            int height = config.renderHeight > 0 ? config.renderHeight : size;
            int supersampling = integer(options, "supersampling", 1);
            double rotation = number(options, "rotation", 135);
            double pitch = number(options, "slant", 36);
            String background = text(options, "background", "#000000");
            boolean transparent = options != null && options.has("transparentBackground") && options.get("transparentBackground").getAsBoolean();
            var views = java.util.List.of(
                    new RenderModels.View("isometric", "正二轴测", rotation, pitch, 1.0, true, width, height, background, transparent, supersampling),
                    new RenderModels.View("isometric-reverse", "反向正二轴测", rotation + 180, pitch, 1.0, true, width, height, background, transparent, supersampling));
            String requestId = payload.get("id").getAsString();
            var request = new RenderModels.Request(2, requestId, text(payload, "filename", "schematic.litematic"), views, null);
            var result = renderer.submit(request, schematic, Duration.ofMillis(config.renderTimeoutMillis), "HTTP", null).join();
            JsonObject response = new JsonObject();
            response.addProperty("version", 1);
            response.addProperty("id", requestId);
            JsonArray images = new JsonArray();
            for (var image : result.images()) {
                JsonObject item = new JsonObject();
                item.addProperty("title", image.name());
                item.addProperty("base64", Base64.getEncoder().encodeToString(Files.readAllBytes(image.path())));
                images.add(item);
            }
            response.add("images", images);
            reply(exchange, 200, response);
        } catch (SecurityException error) {
            reply(exchange, 401, error(error.getMessage()));
        } catch (Throwable error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            log.accept("HTTP v1 渲染失败：" + cause.getMessage());
            reply(exchange, 400, error(cause.getMessage()));
        }
    }

    private void consumeNonce(String nonce) {
        long now = System.currentTimeMillis();
        nonces.entrySet().removeIf(entry -> entry.getValue() < now);
        if (nonce == null || nonces.putIfAbsent(nonce, now + config.maxClockSkewSeconds * 2000L) != null) {
            throw new SecurityException("replayed request nonce");
        }
    }

    private static int integer(JsonObject value, String key, int fallback) { return value != null && value.has(key) ? value.get(key).getAsInt() : fallback; }
    private static double number(JsonObject value, String key, double fallback) { return value != null && value.has(key) ? value.get(key).getAsDouble() : fallback; }
    private static String text(JsonObject value, String key, String fallback) { return value != null && value.has(key) ? value.get(key).getAsString() : fallback; }
    private static JsonObject error(String message) { JsonObject value = new JsonObject(); value.addProperty("version", 1); value.addProperty("error", message); return value; }

    private static void reply(HttpExchange exchange, int status, JsonObject value) throws IOException {
        byte[] bytes = Protocol.GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override public void close() { if (server != null) server.stop(1); server = null; }
}
