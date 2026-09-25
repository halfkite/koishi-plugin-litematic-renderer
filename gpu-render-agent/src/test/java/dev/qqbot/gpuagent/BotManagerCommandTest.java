package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BotManagerCommandTest {
    @Test
    void parsesButtonCommandsWithoutConfusingOrdinalsAndViews() {
        assertEquals(new BotManager.ViewCommand("更多视图", 42, ""), BotManager.parseViewCommand("/更多视图42"));
        assertEquals(new BotManager.ViewCommand("投影视图", 42, "左视图"), BotManager.parseViewCommand("/投影视图42 左视图"));
        assertEquals(new BotManager.ViewCommand("地图视图", 42, ""), BotManager.parseViewCommand("/地图视图42"));
        assertEquals(new BotManager.ViewCommand("地图画模式", 42, ""), BotManager.parseViewCommand("/地图画模式42"));
        assertNull(BotManager.parseViewCommand("/投影视图abc 左视图"));
    }

    @Test
    void additionalViewsUseExactAxisAngles(@TempDir Path root) {
        assertEquals(List.of("正轴视图", "反轴视图", "正视图", "后视图", "左视图", "右视图", "俯视图", "仰视图", "地图视图"),
                BotManager.ADDITIONAL_VIEW_NAMES);
        BotManager manager = new BotManager(root, root.resolve("agent.json"), new AgentConfig(), null, null);
        assertView(manager.auxiliaryView("正视图"), 0, 0);
        assertView(manager.auxiliaryView("后视图"), 180, 0);
        assertView(manager.auxiliaryView("左视图"), 270, 0);
        assertView(manager.auxiliaryView("右视图"), 90, 0);
        assertView(manager.auxiliaryView("俯视图"), 0, 90);
        assertView(manager.auxiliaryView("仰视图"), 0, -90);
        assertEquals("地图视图", manager.mapArtView().name());
        assertEquals("extra-map-art", manager.mapArtView().id());
    }

    private static void assertView(RenderModels.View view, double yaw, double pitch) {
        assertNotNull(view);
        assertEquals(yaw, view.yaw());
        assertEquals(pitch, view.pitch());
    }

    private static final List<String> SEND_COMMANDS = List.of("发送投影");

    @Test
    void sendCommandAcceptsExactName() {
        BotManager.ProjectionSendCommand command = BotManager.parseSendCommand("/发送投影 半筝", SEND_COMMANDS);
        assertNotNull(command);
        assertNull(command.ordinal());
        assertEquals("半筝", command.exactName());
    }

    @Test
    void bareSendCommandDoesNotDefaultToFirstResult() {
        BotManager.ProjectionSendCommand command = BotManager.parseSendCommand("发送投影", SEND_COMMANDS);
        assertNotNull(command);
        assertNull(command.ordinal());
        assertTrue(command.exactName().isBlank());
    }

    @Test
    void acceptsSpacedOrAdjacentOrdinalButNotOldOrdinalKeywordSyntax() {
        for (String text : List.of("/发送投影3", "/发送投影 3")) {
            BotManager.ProjectionSendCommand numbered = BotManager.parseSendCommand(text, SEND_COMMANDS);
            assertNotNull(numbered);
            assertEquals(3, numbered.ordinal());
        }
        assertNull(BotManager.parseSendCommand("/发送投影3 半筝", SEND_COMMANDS));
        assertNull(BotManager.parseSendCommand("/发送投影3半筝", SEND_COMMANDS));
        BotManager.ProjectionSendCommand numericName = BotManager.parseSendCommand("/发送投影 2025建筑", SEND_COMMANDS);
        assertNotNull(numericName);
        assertEquals("2025建筑", numericName.exactName());
    }

    @Test
    void parsesOnlyConfiguredMaterialCommand() {
        BotManager.SendCommand materials = BotManager.parseMaterialsCommand("/导出材料3 半筝", List.of("导出材料"));
        assertNotNull(materials);
        assertEquals(3, materials.index());
        assertEquals("半筝", materials.keyword());

        assertNull(BotManager.parseMaterialsCommand("发送材料2 工厂", List.of("导出材料")));
        assertNull(BotManager.parseMaterialsCommand("材料2 工厂", List.of("导出材料")));
    }

    @Test
    void bareMaterialCommandHasNoImplicitSearchOrdinal() {
        BotManager.SendCommand command = BotManager.parseMaterialsCommand("/导出材料", List.of("导出材料"));
        assertNotNull(command);
        assertEquals(0, command.index());
        assertEquals("", command.keyword());
    }

    @Test
    void parsesProjectionListCommandWithSlashPrefix() {
        assertTrue(BotManager.parseProjectionListCommand("/投影列表", List.of("投影列表")));
        assertFalse(BotManager.parseProjectionListCommand("投影列表 半筝", List.of("投影列表")));
    }

    @Test
    void stripsVisibleBotMentionBeforeCommandParsing() {
        assertEquals("搜索投影 刷", BotManager.normalizeCommandText("@Lbot 搜索投影 刷"));
        assertEquals("搜索投影 刷", BotManager.normalizeCommandText("<@!bot-open-id> 搜索投影 刷"));
        assertEquals("导出材料", BotManager.normalizeCommandText("[CQ:reply,id=render-42]导出材料"));
        assertEquals("", BotManager.normalizeCommandText("@Lbot"));
    }

    @Test
    void extractsQuotedMessageIdsFromBotAdapters() {
        assertEquals("onebot-42", OneBotAdapter.replyIdFromRaw("[CQ:reply,id=onebot-42][CQ:at,qq=1] 导出材料"));
        var officialEvent = Protocol.GSON.fromJson("{\"message_reference\":{\"message_id\":\"qq-42\"}}",
                com.google.gson.JsonObject.class);
        assertEquals("qq-42", OfficialQqAdapter.referencedMessageId(officialEvent));
    }

    @Test
    void searchUsesConfiguredNameOnly() {
        assertNull(BotManager.parseSearchCommand("/投影搜索 半筝", List.of("搜索投影")));
        BotManager.SearchCommand command = BotManager.parseSearchCommand("/查投影 半筝", List.of("查投影"));
        assertNotNull(command);
        assertEquals("半筝", command.keyword());
    }

    @Test
    void acceptsSlashHelpAndConfiguredHelpNames() {
        assertTrue(BotManager.parseHelpCommand("/帮助", List.of("帮助")));
        assertTrue(BotManager.parseHelpCommand("帮助", List.of("帮助")));
        assertFalse(BotManager.parseHelpCommand("帮助列表", List.of("帮助")));
    }

    @Test
    void acceptsIntroductionByMentionAndSlashAliases() {
        assertTrue(BotManager.isIntroductionRequest("", true, false));
        assertTrue(BotManager.parseIntroductionCommand(BotManager.normalizeCommandText("@Lbot 介绍")));
        assertTrue(BotManager.parseIntroductionCommand("/介绍投影BOT"));
        assertTrue(BotManager.isIntroductionPanelCommand("介绍投影BOT"));
        assertTrue(BotManager.isIntroductionPanelCommand("/介绍投影BOT"));
        assertFalse(BotManager.isIntroductionPanelCommand("介绍"));
        assertFalse(BotManager.parseIntroductionCommand("介绍投影BOT帮助"));
    }

    @Test
    void introductionDoesNotInterceptMessagesWithProjectionAttachments() {
        assertFalse(BotManager.isIntroductionRequest("", true, true));
        assertFalse(BotManager.isIntroductionRequest("介绍", true, true));
    }

    @Test
    void helpIsIncludedInCommandSettingsByDefault() {
        AgentConfig config = new AgentConfig();

        assertTrue(config.commandNames("help").contains("帮助"));
        assertTrue(config.commandNames("sendMaterials").contains("导出材料"));
        assertFalse(config.commandNames("sendMaterials").contains("发送材料"));
        assertFalse(config.commandNames("sendMaterials").contains("材料"));
        assertTrue(config.commandNames("projectionList").contains("投影列表"));
    }
}
