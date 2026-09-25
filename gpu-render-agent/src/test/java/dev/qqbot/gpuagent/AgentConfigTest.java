package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AgentConfigTest {
    @Test
    void newConfigGetsNightVisionDisabledByDefault() throws Exception {
        Path file = Files.createTempFile("litematic-agent", ".json");
        try {
            Files.writeString(file, "{}");
            AgentConfig config = AgentConfig.load(file);
            assertFalse(config.nightVisionEnabled);
            assertEquals(15, config.nightVisionLevel);
            assertFalse(config.localMergeEnabled);
            assertTrue(config.showMetadataProjectionName);
            assertTrue(config.showMetadataAuthor);
            assertTrue(config.showMetadataCreatedAt);
            assertTrue(config.showMetadataBlockStats);
            assertTrue(config.showMetadataSize);
            assertTrue(config.showMetadataLitematicVersion);
            assertTrue(config.showMetadataGameVersion);
            assertEquals("full", config.metadataFormat);
            assertTrue(new AgentConfig.BotProfile().autoConnect);
            assertTrue(new AgentConfig.BotProfile().commandRequireMention);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void imageMetadataFormatSurvivesConfigurationLoading() throws Exception {
        Path file = Files.createTempFile("litematic-agent-metadata", ".json");
        try {
            Files.writeString(file, "{\"metadataFormat\":\"image\"}");
            assertEquals("image", AgentConfig.load(file).metadataFormat);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void legacyCacheLimitSplitsIntoRecentAndHistoricalQuotas() throws Exception {
        Path file = Files.createTempFile("litematic-agent-cache-config", ".json");
        try {
            Files.writeString(file, "{\"cacheMaxBytes\":10737418240}");
            AgentConfig config = AgentConfig.load(file);
            assertEquals(8L * 1024 * 1024 * 1024, config.cacheRecentImageMaxBytes);
            assertEquals(2L * 1024 * 1024 * 1024, config.cacheHistoricalImageMaxBytes);
            assertEquals(config.cacheMaxBytes,
                    config.cacheRecentImageMaxBytes + config.cacheHistoricalImageMaxBytes);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void firstStartCreatesWebCredentialsAndKeepsDesktopWebDisabled() throws Exception {
        Path directory = Files.createTempDirectory("litematic-agent-config-");
        Path file = directory.resolve("agent.json");
        try {
            AgentConfig config = AgentConfig.load(file);
            assertFalse(config.webEnabled);
            assertEquals("admin", config.webUsername);
            assertEquals(12, config.webPassword.length());
            assertTrue(config.webPassword.chars().allMatch(Character::isDigit));
            assertTrue(config.generatedWebPassword());
            assertTrue(Files.readString(directory.resolve("web-credentials.txt")).contains(config.webPassword));
            assertTrue(Files.isRegularFile(file));
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path item : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
            }
        }
    }

    @Test
    void nightVisionLevelIsClampedWhenLoaded() throws Exception {
        Path file = Files.createTempFile("litematic-agent", ".json");
        try {
            Files.writeString(file, "{\"nightVisionEnabled\":false,\"nightVisionLevel\":99}");
            AgentConfig high = AgentConfig.load(file);
            assertFalse(high.nightVisionEnabled);
            assertEquals(15, high.nightVisionLevel);

            Files.writeString(file, "{\"nightVisionLevel\":0}");
            assertEquals(1, AgentConfig.load(file).nightVisionLevel);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void localConfigCanChangeWebPasswordAndCredentialHint() throws Exception {
        Path directory = Files.createTempDirectory("litematic-agent-password-");
        Path file = directory.resolve("agent.json");
        try {
            AgentConfig config = AgentConfig.load(file);
            config.changeWebPassword(file, "new-password", "new-password");
            AgentConfig loaded = AgentConfig.load(file);
            assertEquals("new-password", loaded.webPassword);
            assertFalse(loaded.webPasswordChangeNotice);
            assertTrue(Files.readString(directory.resolve("web-credentials.txt")).contains("密码：new-password"));
            assertThrows(IllegalArgumentException.class, () -> config.changeWebPassword(file, "one", "two"));
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path item : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
            }
        }
    }

    @Test
    void localConfigCanChangeWebUsernameAndCredentialHint() throws Exception {
        Path directory = Files.createTempDirectory("litematic-agent-username-");
        Path file = directory.resolve("agent.json");
        try {
            AgentConfig config = AgentConfig.load(file);
            config.webUsername = "operator_01";
            config.save(file);
            AgentConfig.writeCredentialHintForCurrentUser(file, config.webUsername, config.webPassword);
            AgentConfig loaded = AgentConfig.load(file);
            assertEquals("operator_01", loaded.webUsername);
            assertTrue(Files.readString(directory.resolve("web-credentials.txt")).contains("用户名：operator_01"));
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path item : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
            }
        }
    }

    @Test
    void cloudMergeLayoutSupportsThreeSendModesAndMigratesOldSeparateValues() throws Exception {
        Path file = Files.createTempFile("litematic-agent", ".json");
        try {
            Files.writeString(file, "{\"cloudMergeLayout\":\"diagonal\"}");
            assertEquals("horizontal", AgentConfig.load(file).cloudMergeLayout);

            Files.writeString(file, "{\"cloudMergeLayout\":\"VERTICAL\"}");
            assertEquals("vertical", AgentConfig.load(file).cloudMergeLayout);

            Files.writeString(file, "{\"cloudMergeLayout\":\"vertical-separate\"}");
            assertEquals("separate", AgentConfig.load(file).cloudMergeLayout);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void localMergeSettingIsLoaded() throws Exception {
        Path file = Files.createTempFile("litematic-agent", ".json");
        try {
            Files.writeString(file, "{\"localMergeEnabled\":true}");
            assertTrue(AgentConfig.load(file).localMergeEnabled);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void oldRenderImageMergeSettingMigratesToSeparateMode() throws Exception {
        Path file = Files.createTempFile("litematic-agent", ".json");
        try {
            Files.writeString(file, "{\"cloudMergeLayout\":\"vertical\",\"mergeRenderImages\":false}");
            assertEquals("separate", AgentConfig.load(file).cloudMergeLayout);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void separateImageSendWinsAcrossAgentAndCloudRequest() {
        assertEquals("separate", AgentConfig.effectiveImageSendLayout("horizontal-separate", "vertical"));
        assertEquals("separate", AgentConfig.effectiveImageSendLayout("horizontal", "vertical-separate"));
        assertEquals("vertical", AgentConfig.effectiveImageSendLayout("horizontal", "vertical"));
    }

    @Test
    void globalSeparateModeAlsoOverridesOfficialAccountDefault() {
        assertEquals("separate",
                AgentConfig.effectiveOfficialImageSendLayout("horizontal-separate", "horizontal"));
        assertEquals("separate",
                AgentConfig.effectiveOfficialImageSendLayout("vertical-separate", "horizontal"));
        assertEquals("separate",
                AgentConfig.effectiveOfficialImageSendLayout("horizontal", "vertical-separate"));
        assertEquals("vertical",
                AgentConfig.effectiveOfficialImageSendLayout("vertical", "horizontal"));
    }

    @Test
    void groupMessageModeKeepsAnyOption() throws Exception {
        Path file = Files.createTempFile("litematic-agent", ".json");
        try {
            Files.writeString(file, "{\"botProfiles\":[{\"groupMessageMode\":\"ANY\"}]}");
            assertEquals("any", AgentConfig.load(file).botProfiles.getFirst().groupMessageMode);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void commandMentionRequirementIsIndependentFromFileMessageMode() throws Exception {
        Path file = Files.createTempFile("litematic-agent", ".json");
        try {
            Files.writeString(file, "{\"botProfiles\":[{\"groupMessageMode\":\"received\",\"commandRequireMention\":false}]}");
            AgentConfig.BotProfile profile = AgentConfig.load(file).botProfiles.getFirst();
            assertEquals("received", profile.groupMessageMode);
            assertFalse(profile.commandRequireMention);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void accountFileLimitsDefaultToGlobalAndClampNegativeOverrides() throws Exception {
        Path file = Files.createTempFile("litematic-agent", ".json");
        try {
            Files.writeString(file, "{\"maxFileSizeKb\":2048,\"privateMaxFileSizeKb\":4096,\"botProfiles\":[{\"maxFileSizeKb\":-1,\"privateMaxFileSizeKb\":512}]}");
            AgentConfig config = AgentConfig.load(file);
            AgentConfig.BotProfile profile = config.botProfiles.getFirst();
            assertEquals(0, profile.maxFileSizeKb);
            assertEquals(512, profile.privateMaxFileSizeKb);
            assertEquals(2048, BotManager.effectiveFileLimitKb(config, profile, false));
            assertEquals(512, BotManager.effectiveFileLimitKb(config, profile, true));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void viewBrightnessDefaultsToNormalAndIsClamped() {
        AgentConfig.ViewEntry normal = new AgentConfig.ViewEntry("view", "测试", 0, 0, 1.0,
                1024, 1024, 1, "#000000", false, true, null);
        assertEquals(1.0, normal.brightnessFactor());

        AgentConfig.ViewEntry high = new AgentConfig.ViewEntry("view", "测试", 0, 0, 1.0,
                1024, 1024, 1, "#000000", false, true, 9.0);
        assertEquals(3.0, high.brightnessFactor());
    }

    @Test
    void migratesOldMaterialNamesAndPreservesCustomAliases() throws Exception {
        Path file = Files.createTempFile("litematic-agent-command-migration", ".json");
        try {
            Files.writeString(file, """
                    {"webPassword":"secret","commands":[{"id":"sendMaterials","name":"发送材料","aliases":[{"name":"导出材料","enabled":true},{"name":"材料","enabled":true},{"name":"物料清单","enabled":true}]}]}
                    """);
            AgentConfig config = AgentConfig.load(file);
            assertEquals(java.util.List.of("导出材料", "物料清单"), config.commandNames("sendMaterials"));
            assertEquals(4, config.commandAliasMigrationVersion);
            assertFalse(Files.readString(file).contains("发送材料"));
            assertEquals(java.util.List.of("导出材料", "物料清单"), AgentConfig.load(file).commandNames("sendMaterials"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void keepsDisabledCustomAliasWhenPrimaryMaterialNameWasChanged() throws Exception {
        Path file = Files.createTempFile("litematic-agent-command-disabled", ".json");
        try {
            Files.writeString(file, """
                    {"webPassword":"secret","commands":[{"id":"sendMaterials","name":"导出物料","aliases":[{"name":"导出材料","enabled":false}]}]}
                    """);
            assertEquals(java.util.List.of("导出物料"), AgentConfig.load(file).commandNames("sendMaterials"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void migratesLegacySearchCommandNameToSearchProjectionOnce() throws Exception {
        Path file = Files.createTempFile("litematic-agent-search-command-migration", ".json");
        try {
            Files.writeString(file, """
                    {"webPassword":"secret","commandAliasMigrationVersion":1,"commands":[{"id":"search","name":"投影搜索"}]}
                    """);
            AgentConfig config = AgentConfig.load(file);
            assertEquals(1, config.commandNames("search").size());
            assertEquals("搜索投影", config.commandNames("search").getFirst());
            assertEquals(4, config.commandAliasMigrationVersion);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void removesRetiredRenderSearchCommandFromExistingConfig() throws Exception {
        Path file = Files.createTempFile("litematic-agent-retired-command", ".json");
        try {
            Files.writeString(file, """
                    {"webPassword":"secret","commandAliasMigrationVersion":3,"commands":[{"id":"renderSearch","name":"渲染搜索","aliases":[{"name":"重渲染","enabled":true}]}]}
                    """);
            AgentConfig config = AgentConfig.load(file);
            assertEquals(4, config.commandAliasMigrationVersion);
            assertTrue(config.commandNames("renderSearch").isEmpty());
            assertFalse(Files.readString(file).contains("渲染搜索"));
            assertFalse(Files.readString(file).contains("重渲染"));
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
