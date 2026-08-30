package dev.qqbot.gpuagent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

final class Protocol {
    static final Gson GSON = new Gson();
    static final int VERSION = 2;

    private Protocol() {}

    static String hmac(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    static boolean secureHexEquals(String supplied, String expected) {
        try {
            return MessageDigest.isEqual(HexFormat.of().parseHex(supplied), HexFormat.of().parseHex(expected));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static ByteBuffer binary(String type, String taskId, String attachmentId, String name,
                             int width, int height, byte[] payload) {
        JsonObject header = new JsonObject();
        header.addProperty("type", type);
        header.addProperty("taskId", taskId);
        header.addProperty("attachmentId", attachmentId);
        if (name != null) header.addProperty("name", name);
        if (width > 0) header.addProperty("width", width);
        if (height > 0) header.addProperty("height", height);
        byte[] json = GSON.toJson(header).getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(4 + json.length + payload.length).putInt(json.length).put(json).put(payload).flip();
    }

    static BinaryFrame decodeBinary(ByteBuffer source) {
        ByteBuffer value = source.slice();
        if (value.remaining() < 5) throw new IllegalArgumentException("binary frame is truncated");
        int headerLength = value.getInt();
        if (headerLength <= 0 || headerLength > 64 * 1024 || headerLength > value.remaining()) {
            throw new IllegalArgumentException("invalid binary frame header");
        }
        byte[] headerBytes = new byte[headerLength];
        value.get(headerBytes);
        byte[] payload = new byte[value.remaining()];
        value.get(payload);
        return new BinaryFrame(GSON.fromJson(new String(headerBytes, StandardCharsets.UTF_8), JsonObject.class), payload);
    }

    record BinaryFrame(JsonObject header, byte[] payload) {}
}
