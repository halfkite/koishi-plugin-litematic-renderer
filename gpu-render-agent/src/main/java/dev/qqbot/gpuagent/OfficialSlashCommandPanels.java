package dev.qqbot.gpuagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Builds the self-managed QQ official slash-command panels. */
final class OfficialSlashCommandPanels {
    static final int ITEMS_PER_PANEL = 20;
    static final int MAX_PANELS_PER_BOT = 20;
    private static final String REMARK_PREFIX = "litematic-gpu-agent:commands:v1:";
    private static final Set<String> HIDDEN_REDUNDANT_COMMANDS = Set.of("投影搜索", "发送材料", "材料");

    private OfficialSlashCommandPanels() {}

    static List<JsonObject> items(AgentConfig config) {
        validateConfiguredNames(config);
        Map<String, JsonObject> items = new LinkedHashMap<>();
        addNames(items, config.commandNames("search"), "关键词后回车搜索缓存投影");
        for (String name : config.commandNames("sendProjection")) {
            add(items, name, "输入固定编号或完整名称发送投影");
        }
        addNames(items, config.commandNames("sendMaterials"), "引用渲染图导出对应材料表");
        addNames(items, config.commandNames("projectionList"), "导出全部缓存投影CSV清单");
        addNames(items, config.commandNames("help"), "查看机器人使用帮助");
        addNames(items, config.commandNames("introduction"), "查看投影机器人介绍");
        addNames(items, config.commandNames("moreViews"), "按编号打开视图选择");
        addNames(items, config.commandNames("projectionView"), "按编号与视角生成视图");
        addNames(items, config.commandNames("mapView"), "生成地图配色视图");
        return List.copyOf(items.values());
    }

    static List<List<JsonObject>> pages(AgentConfig config) {
        List<JsonObject> items = items(config);
        List<List<JsonObject>> pages = new ArrayList<>();
        for (int offset = 0; offset < items.size(); offset += ITEMS_PER_PANEL) {
            pages.add(List.copyOf(items.subList(offset, Math.min(items.size(), offset + ITEMS_PER_PANEL))));
        }
        return List.copyOf(pages);
    }

    static String remark(String scope, int page) { return REMARK_PREFIX + scope + ":" + page; }

    static boolean isManagedRemark(String remark) {
        return remark != null && remark.startsWith(REMARK_PREFIX);
    }

    static int pageFromRemark(String remark, String scope) {
        if (remark == null || !remark.startsWith(REMARK_PREFIX + scope + ":")) return -1;
        try { return Integer.parseInt(remark.substring((REMARK_PREFIX + scope + ":").length())); }
        catch (NumberFormatException ignored) { return -1; }
    }

    static JsonObject createBody(String scope, int page, List<JsonObject> items) {
        JsonObject panel = panel(items, remark(scope, page));
        JsonObject body = new JsonObject();
        body.addProperty("scope", scope);
        body.addProperty("target_type", "all");
        body.add("panel", panel);
        return body;
    }

    static JsonObject updateBody(String scope, int page, List<JsonObject> items) {
        JsonObject body = new JsonObject();
        body.add("panel", panel(items, remark(scope, page)));
        return body;
    }

    static JsonObject panel(List<JsonObject> items, String remark) {
        JsonArray array = new JsonArray();
        for (JsonObject item : items) array.add(item.deepCopy());
        JsonObject panel = new JsonObject();
        panel.add("items", array);
        panel.addProperty("remark", remark);
        return panel;
    }

    static boolean matchesPanel(JsonObject current, JsonObject desired) {
        if (current == null || desired == null || !value(current, "remark").equals(value(desired, "remark"))) return false;
        JsonArray left = current.has("items") && current.get("items").isJsonArray()
                ? current.getAsJsonArray("items") : new JsonArray();
        JsonArray right = desired.has("items") && desired.get("items").isJsonArray()
                ? desired.getAsJsonArray("items") : new JsonArray();
        if (left.size() != right.size()) return false;
        for (int index = 0; index < left.size(); index++) {
            if (!left.get(index).isJsonObject() || !right.get(index).isJsonObject()) return false;
            JsonObject actual = left.get(index).getAsJsonObject();
            JsonObject expected = right.get(index).getAsJsonObject();
            for (String key : List.of("type", "name", "desc", "link")) {
                if (!value(actual, key).equals(value(expected, key))) return false;
            }
            if (flag(actual, "only_admin") != flag(expected, "only_admin")) return false;
        }
        return true;
    }

    static void validateConfiguredNames(AgentConfig config) {
        Map<String, String> owners = new LinkedHashMap<>();
        for (String id : List.of("search", "sendProjection", "sendMaterials", "projectionList", "help",
                "introduction", "moreViews", "projectionView", "mapView")) {
            for (String name : config.commandNames(id)) {
                registerName(owners, name, id);
            }
        }
    }

    private static void registerName(Map<String, String> owners, String name, String id) {
        if (name == null || name.isBlank() || name.startsWith("/") || name.chars().anyMatch(Character::isWhitespace)
                || displayWidth(name) > 14) {
            throw new IllegalArgumentException("指令名称无效或超过 QQ 面板的 14 字符限制：" + name);
        }
        String previous = owners.putIfAbsent(name.toLowerCase(Locale.ROOT), id);
        if (previous != null && !previous.equals(id)) throw new IllegalArgumentException("指令名称重复：" + name);
    }

    private static String value(JsonObject item, String key) {
        JsonElement element = item.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static boolean flag(JsonObject item, String key) {
        JsonElement element = item.get(key);
        return element != null && !element.isJsonNull() && element.getAsBoolean();
    }

    static JsonObject item(String name, String description) {
        JsonObject item = new JsonObject();
        item.addProperty("type", "command");
        item.addProperty("name", name);
        item.addProperty("desc", description);
        item.addProperty("only_admin", false);
        return item;
    }

    static int displayWidth(String value) {
        int width = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
            boolean wide = block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                    || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                    || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                    || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                    || block == Character.UnicodeBlock.HIRAGANA
                    || block == Character.UnicodeBlock.KATAKANA;
            width += wide ? 2 : 1;
            offset += Character.charCount(codePoint);
        }
        return width;
    }

    private static void addNames(Map<String, JsonObject> items, List<String> names, String description) {
        for (String name : names) add(items, name, description);
    }

    private static void add(Map<String, JsonObject> items, String rawName, String description) {
        if (rawName == null) return;
        String name = rawName.trim();
        if (name.startsWith("/")) name = name.substring(1);
        if (name.isBlank() || HIDDEN_REDUNDANT_COMMANDS.contains(name)
                || displayWidth(name) > 14 || displayWidth(description) > 30) return;
        items.putIfAbsent(name.toLowerCase(Locale.ROOT), item(name, description));
    }
}
