package dev.qqbot.gpuagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Stores group IDs only; announcement sends never reuse an incoming message ID. */
final class AnnouncementGroups {
    private final Path file;
    private final Consumer<String> log;
    private final Map<String, LinkedHashSet<String>> groups = new LinkedHashMap<>();

    AnnouncementGroups(Path file, Consumer<String> log) {
        this.file = file;
        this.log = log;
        try {
            if (!Files.isRegularFile(file)) return;
            JsonObject root = Protocol.GSON.fromJson(Files.readString(file), JsonObject.class);
            if (root == null) return;
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (!entry.getValue().isJsonArray()) continue;
                for (JsonElement id : entry.getValue().getAsJsonArray()) {
                    if (id.isJsonPrimitive() && valid(id.getAsString()))
                        groups.computeIfAbsent(entry.getKey(), ignored -> new LinkedHashSet<>()).add(id.getAsString());
                }
            }
        } catch (Exception error) { log.accept("公告群列表读取失败：" + error.getMessage()); }
    }

    synchronized List<String> list(String profileId) {
        return new ArrayList<>(groups.getOrDefault(profileId, new LinkedHashSet<>()));
    }

    synchronized void add(String profileId, String groupId) {
        if (!valid(profileId) || !valid(groupId)) throw new IllegalArgumentException("群标识不能为空或包含空白字符");
        if (groups.computeIfAbsent(profileId, ignored -> new LinkedHashSet<>()).add(groupId)) save();
    }

    synchronized void remove(String profileId, String groupId) {
        LinkedHashSet<String> values = groups.get(profileId);
        if (values != null && values.remove(groupId)) save();
    }

    synchronized ImportResult importLegacy(Path index, Set<String> accountIds) throws IOException {
        if (!Files.isRegularFile(index)) throw new IOException("未找到旧渲染消息索引：" + index);
        if (Files.size(index) > 32L * 1024 * 1024) throw new IOException("旧索引超过 32 MB，已取消读取");
        JsonArray rows;
        try { rows = Protocol.GSON.fromJson(Files.readString(index), JsonArray.class); }
        catch (RuntimeException error) { throw new IOException("旧渲染消息索引格式无效", error); }
        if (rows == null) throw new IOException("旧渲染消息索引为空");
        int found = 0;
        int added = 0;
        for (JsonElement element : rows) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject row = element.getAsJsonObject();
            if (row.has("私聊") && row.get("私聊").isJsonPrimitive()
                    && row.get("私聊").getAsBoolean()) continue;
            String account = string(row, "账号ID");
            String group = string(row, "群ID");
            if (!accountIds.contains(account) || !valid(group)) continue;
            found++;
            if (groups.computeIfAbsent(account, ignored -> new LinkedHashSet<>()).add(group)) added++;
        }
        if (added > 0) save();
        return new ImportResult(found, added);
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    record ImportResult(int matchingRecords, int addedGroups) {}

    private static boolean valid(String value) {
        return value != null && !value.isBlank() && value.length() <= 128
                && value.chars().noneMatch(Character::isWhitespace);
    }

    private void save() {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, LinkedHashSet<String>> entry : groups.entrySet()) {
            JsonArray values = new JsonArray();
            entry.getValue().forEach(values::add);
            root.add(entry.getKey(), values);
        }
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, Protocol.GSON.toJson(root));
            try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) { log.accept("公告群列表保存失败：" + error.getMessage()); }
    }
}
