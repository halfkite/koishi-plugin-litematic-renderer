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
    void parsesProjectionVersionConversionByOrdinalAndDefaultsToVersionSix() {
        assertEquals(new BotManager.ConvertProjectionVersionCommand(1321, 6),
                BotManager.parseConvertProjectionVersionCommand("/转换投影版本 1321", List.of("转换投影版本")));
        assertEquals(new BotManager.ConvertProjectionVersionCommand(1321, 7),
                BotManager.parseConvertProjectionVersionCommand("/转换投影版本1321 7", List.of("转换投影版本")));
        assertEquals(new BotManager.ConvertProjectionVersionCommand(42, 6),
                BotManager.parseConvertProjectionVersionCommand("转换版本 42", List.of("转换版本")));
        assertNull(BotManager.parseConvertProjectionVersionCommand("/转换投影版本", List.of("转换投影版本")));
        assertNull(BotManager.parseConvertProjectionVersionCommand("/转换投影版本 42 5", List.of("转换投影版本")));
        assertNull(BotManager.parseConvertProjectionVersionCommand("/版本转换 6", List.of("转换投影版本")));
    }

    @Test
    void conversionKeepsTheOriginalNameAndAppendsVersionSuffix() {
        assertEquals("投影名称-v6.litematic",
                BotManager.convertedProjectionFilename("投影名称.litematic", 6));
        assertEquals("原始投影 名称-v7.LITEMATIC",
                BotManager.convertedProjectionFilename("原始投影 名称.LITEMATIC", 7));
        assertEquals("3074号 投影名称 已转换为 Litematic 格式 V7，V5对应1.13-1.17；V6对应1.18-1.20.4；V7对应1.21+；目标游戏没有的方块仍无法显示",
                BotManager.convertedProjectionSuccessMessage(3074, "投影名称.litematic", 7));
    }

    @Test
    void renderedResultButtonsAreConfigurableAndPutMapViewInsideMoreViews(@TempDir Path temporaryDirectory) {
        AgentConfig config = new AgentConfig();
        BotManager manager = new BotManager(temporaryDirectory, temporaryDirectory.resolve("agent.json"),
                config, null, null);

        List<BotAction> defaults = manager.projectionActions(42);
        assertEquals(List.of("导出材料", "更多视图", "转换为V6"),
                defaults.stream().map(BotAction::label).toList());
        assertEquals("/转换投影版本42 6", defaults.getLast().command());

        AgentConfig.CommandDefinition convertCommand = config.commands.stream()
                .filter(command -> "convertProjectionVersion".equals(command.id)).findFirst().orElseThrow();
        convertCommand.name = "格式转换";
        assertEquals("/格式转换42 6", manager.projectionActions(42).getLast().command());

        convertCommand.enabled = false;
        assertEquals(List.of("导出材料", "更多视图"),
                manager.projectionActions(42).stream().map(BotAction::label).toList());
        assertTrue(BotManager.ADDITIONAL_VIEW_NAMES.contains("地图视图"));
    }

    @Test
    void commandCanAddAConfiguredPrefillActionToRenderedResults(@TempDir Path temporaryDirectory) {
        AgentConfig config = new AgentConfig();
        AgentConfig.CommandDefinition search = config.commands.stream()
                .filter(command -> "search".equals(command.id)).findFirst().orElseThrow();
        search.showInRenderActions = true;
        search.renderActionLabel = "搜索更多";

        BotManager manager = new BotManager(temporaryDirectory, temporaryDirectory.resolve("agent.json"),
                config, null, null);
        BotAction action = manager.projectionActions(42).getLast();

        assertEquals("搜索更多", action.label());
        assertEquals("/搜索投影 ", action.command());
        assertFalse(action.enter());
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
        assertTrue(config.commandNames("convertProjectionVersion").contains("转换投影版本"));
    }
}
