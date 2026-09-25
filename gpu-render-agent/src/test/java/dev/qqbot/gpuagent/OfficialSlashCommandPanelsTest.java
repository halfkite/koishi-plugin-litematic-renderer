package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OfficialSlashCommandPanelsTest {
    @Test
    void publishesOnlyConfiguredMaterialName() {
        List<com.google.gson.JsonObject> items = OfficialSlashCommandPanels.items(new AgentConfig());
        List<String> names = items.stream().map(item -> item.get("name").getAsString()).toList();

        assertTrue(names.containsAll(List.of("搜索投影",
                "发送投影", "导出材料", "投影列表", "帮助", "介绍投影BOT")));
        assertFalse(names.stream().anyMatch(name -> name.startsWith("渲染搜索")));
        assertFalse(names.contains("发送投影1"));
        assertFalse(names.contains("投影搜索"));
        assertFalse(names.contains("发送材料"));
        assertFalse(names.contains("材料"));
        assertTrue(items.stream().allMatch(item -> OfficialSlashCommandPanels.displayWidth(item.get("name").getAsString()) <= 14));
        assertTrue(items.stream().allMatch(item -> OfficialSlashCommandPanels.displayWidth(item.get("desc").getAsString()) <= 30));
    }

    @Test
    void pagesAtOfficialItemLimitAndBuildsGlobalPanelRequest() {
        AgentConfig config = new AgentConfig();
        config.commands.getFirst().aliases = java.util.stream.IntStream.range(0, 25)
                .mapToObj(index -> new AgentConfig.CommandAlias("搜" + index, true)).toList();

        List<List<com.google.gson.JsonObject>> pages = OfficialSlashCommandPanels.pages(config);
        assertTrue(pages.size() >= 2);
        assertTrue(pages.stream().allMatch(page -> page.size() <= OfficialSlashCommandPanels.ITEMS_PER_PANEL));

        var body = OfficialSlashCommandPanels.createBody("group", 1, pages.getFirst());
        assertEquals("group", body.get("scope").getAsString());
        assertEquals("all", body.get("target_type").getAsString());
        assertEquals(OfficialSlashCommandPanels.remark("group", 1), body.getAsJsonObject("panel").get("remark").getAsString());
    }

    @Test
    void disabledAliasesAreNotPublished() {
        AgentConfig config = new AgentConfig();
        AgentConfig.CommandDefinition materialCommand = config.commands.stream()
                .filter(command -> "sendMaterials".equals(command.id)).findFirst().orElseThrow();
        materialCommand.aliases.add(new AgentConfig.CommandAlias("物料清单", false));

        assertFalse(OfficialSlashCommandPanels.items(config).stream()
                .anyMatch(item -> "物料清单".equals(item.get("name").getAsString())));
        assertTrue(OfficialSlashCommandPanels.items(config).stream()
                .anyMatch(item -> "导出材料".equals(item.get("name").getAsString())));
    }

    @Test
    void updatePayloadWrapsPanelAndRenameRemovesOldNames() {
        AgentConfig config = new AgentConfig();
        AgentConfig.CommandDefinition material = config.commands.stream()
                .filter(command -> "sendMaterials".equals(command.id)).findFirst().orElseThrow();
        var oldPanel = OfficialSlashCommandPanels.panel(
                OfficialSlashCommandPanels.pages(config).getFirst(), OfficialSlashCommandPanels.remark("group", 1));
        material.name = "导出物料";
        material.aliases.add(new AgentConfig.CommandAlias("导出材料", true));
        material.aliases.add(new AgentConfig.CommandAlias("物料清单", true));
        List<String> names = OfficialSlashCommandPanels.items(config).stream()
                .map(item -> item.get("name").getAsString()).toList();
        assertTrue(names.containsAll(List.of("导出物料", "导出材料", "物料清单")));

        material.aliases.removeIf(alias -> "导出材料".equals(alias.name));
        var desired = OfficialSlashCommandPanels.pages(config).getFirst();
        var body = OfficialSlashCommandPanels.updateBody("group", 1, desired);
        assertTrue(body.has("panel"));
        assertFalse(body.has("items"));
        assertFalse(body.getAsJsonObject("panel").getAsJsonArray("items").asList().stream()
                .anyMatch(item -> "导出材料".equals(item.getAsJsonObject().get("name").getAsString())));
        assertFalse(OfficialSlashCommandPanels.matchesPanel(oldPanel, body.getAsJsonObject("panel")));
        assertTrue(OfficialSlashCommandPanels.matchesPanel(body.getAsJsonObject("panel"), body.getAsJsonObject("panel")));

        var withAlias = body.getAsJsonObject("panel");
        material.aliases.getFirst().enabled = false;
        var withoutAlias = OfficialSlashCommandPanels.updateBody("group", 1,
                OfficialSlashCommandPanels.pages(config).getFirst()).getAsJsonObject("panel");
        assertFalse(OfficialSlashCommandPanels.matchesPanel(withAlias, withoutAlias));
    }

    @Test
    void rejectsNamesThatCannotAppearInTheOfficialPanel() {
        AgentConfig config = new AgentConfig();
        config.commands.stream().filter(command -> "sendMaterials".equals(command.id)).findFirst().orElseThrow()
                .aliases.add(new AgentConfig.CommandAlias("这是一条过长而无法显示在面板里的指令", true));
        assertThrows(IllegalArgumentException.class, () -> OfficialSlashCommandPanels.validateConfiguredNames(config));
    }
}
