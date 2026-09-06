package dev.qqbot.gpuagent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** 仅使用 JDK 实现 OneBot 反向 WebSocket 所需的握手和文本帧。 */
final class OneBotWebSocketServer implements AutoCloseable {
    interface Handler {
        void onOpen(Channel channel);
        void onText(Channel channel, String text);
        void onClose(Channel channel);
    }

    static final class Channel implements AutoCloseable {
        private final Socket socket;
        private final OutputStream output;
        private final Consumer<String> receiver;
        private final Runnable closedCallback;
        private final Object writeLock = new Object();
        private volatile boolean closed;

        Channel(Socket socket, Consumer<String> receiver, Runnable closedCallback) throws IOException {
            this.socket = socket; this.output = socket.getOutputStream(); this.receiver = receiver; this.closedCallback = closedCallback;
        }

        void readLoop() {
            try {
                InputStream input = socket.getInputStream();
                while (!closed) {
                    int first = input.read();
                    if (first < 0) break;
                    int second = input.read();
                    if (second < 0) break;
                    boolean masked = (second & 0x80) != 0;
                    long length = second & 0x7f;
                    if (length == 126) length = ((input.read() & 0xffL) << 8) | (input.read() & 0xffL);
                    else if (length == 127) {
                        length = 0;
                        for (int i = 0; i < 8; i++) length = (length << 8) | (input.read() & 0xffL);
                    }
                    if (length > 64L * 1024 * 1024) throw new IOException("OneBot WebSocket 帧过大");
                    byte[] mask = masked ? input.readNBytes(4) : new byte[0];
                    if (masked && mask.length != 4) break;
                    byte[] payload = input.readNBytes(Math.toIntExact(length));
                    if (payload.length != length) break;
                    if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
                    int opcode = first & 0x0f;
                    if (opcode == 1) receiver.accept(new String(payload, StandardCharsets.UTF_8));
                    else if (opcode == 8) break;
                    else if (opcode == 9) sendFrame(10, payload);
                }
            } catch (Throwable ignored) { }
            finally { close(); }
        }

        void sendText(String text) throws IOException { sendFrame(1, text.getBytes(StandardCharsets.UTF_8)); }

        private void sendFrame(int opcode, byte[] payload) throws IOException {
            synchronized (writeLock) {
                if (closed) throw new IOException("OneBot WebSocket 已断开");
                output.write(0x80 | opcode);
                if (payload.length <= 125) output.write(payload.length);
                else if (payload.length <= 65_535) {
                    output.write(126); output.write((payload.length >>> 8) & 0xff); output.write(payload.length & 0xff);
                } else {
                    output.write(127); long length = payload.length;
                    for (int shift = 56; shift >= 0; shift -= 8) output.write((int) (length >>> shift) & 0xff);
                }
                output.write(payload); output.flush();
            }
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            try { socket.close(); } catch (IOException ignored) { }
            try { closedCallback.run(); } catch (Throwable ignored) { }
        }
    }

    private final String host;
    private final int port;
    private final String path;
    private final String token;
    private final Handler handler;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean closed;
    private volatile ServerSocket server;
    private volatile Channel channel;

    OneBotWebSocketServer(String host, int port, String path, String token, Handler handler) {
        this.host = host; this.port = port; this.path = path; this.token = token == null ? "" : token; this.handler = handler;
    }

    void start() throws IOException {
        server = new ServerSocket();
        server.bind(new InetSocketAddress(host, port));
        workers.submit(() -> {
            while (!closed) try { accept(server.accept()); } catch (IOException error) { if (!closed) handlerError(error); }
        });
    }

    private void accept(Socket socket) {
        workers.submit(() -> {
            try {
                Map<String, String> headers = readHandshake(socket.getInputStream());
                if (!pathMatches(headers.get(":path")) || !auth(headers)) { socket.close(); return; }
                String key = headers.get("sec-websocket-key");
                if (key == null || key.isBlank()) { socket.close(); return; }
                String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                OutputStream output = socket.getOutputStream();
                output.write(("HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                output.flush();
                java.util.concurrent.atomic.AtomicReference<Channel> reference = new java.util.concurrent.atomic.AtomicReference<>();
                Channel next = new Channel(socket, text -> {
                    Channel current = reference.get();
                    if (current != null) handler.onText(current, text);
                }, () -> {
                    Channel current = reference.getAndSet(null);
                    if (current != null) handler.onClose(current);
                    if (channel == current) channel = null;
                });
                reference.set(next);
                Channel previous = channel; channel = next;
                if (previous != null) previous.close();
                handler.onOpen(next);
                next.readLoop();
            } catch (Throwable error) { handlerError(error); try { socket.close(); } catch (IOException ignored) {} }
        });
    }

    private boolean pathMatches(String value) { return value != null && path.equals(value.split("\\?", 2)[0]); }
    private boolean auth(Map<String, String> headers) {
        if (token.isBlank()) return true;
        String supplied = headers.get("authorization");
        return supplied != null && (supplied.equals("Bearer " + token) || supplied.equals(token));
    }

    private Map<String, String> readHandshake(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int previous = 0, current;
        while (bytes.size() < 16 * 1024 && (current = input.read()) >= 0) {
            bytes.write(current);
            if (previous == '\r' && current == '\n' && bytes.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) break;
            previous = current;
        }
        String text = bytes.toString(StandardCharsets.US_ASCII);
        String[] lines = text.split("\\r\\n");
        Map<String, String> result = new HashMap<>();
        if (lines.length > 0) {
            String[] request = lines[0].split(" ", 3);
            if (request.length >= 2) result.put(":path", request[1]);
        }
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) result.put(lines[i].substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT), lines[i].substring(colon + 1).trim());
        }
        return result;
    }

    private void handlerError(Throwable error) { }

    Channel currentChannel() { return channel; }

    @Override public void close() {
        closed = true;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        if (channel != null) channel.close();
        workers.shutdownNow();
    }
}
