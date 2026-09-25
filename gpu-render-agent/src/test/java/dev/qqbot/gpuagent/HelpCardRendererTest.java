package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class HelpCardRendererTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void helpTextContainsRequestedCommandsAndOrdinalNote() {
        String text = HelpCardRenderer.text();

        assertTrue(text.contains("群文件自动渲染需要群主开放群内全部消息权限。"));
        assertTrue(text.contains("/搜索投影 关键词：显示缓存投影的编号与名称（图片）。"));
        assertTrue(text.contains("/发送投影 123 或 /发送投影123：按编号发送渲染图和原文件。"));
        assertTrue(text.contains("/发送投影 完整名称：按不含后缀的名称精确匹配"));
        assertFalse(text.contains("渲染搜索"));
        assertTrue(text.contains("/导出材料123：导出该编号的材料表；引用渲染图时可只发 /导出材料。"));
        assertFalse(text.contains("/投影搜索"));
        assertFalse(text.contains("/发送材料"));
        assertTrue(text.contains("/投影列表：导出全部缓存投影的 CSV 清单。"));
        assertTrue(text.contains("/更多视图123："));
        assertTrue(text.contains("/投影视图123 正视图："));
        assertTrue(text.contains("/地图视图123："));
        assertTrue(text.contains("/介绍投影BOT："));
        assertTrue(text.contains("/帮助：显示指令帮助。"));
    }

    @Test
    void helpUsesRenamedCommands() {
        AgentConfig config = new AgentConfig();
        config.commands.stream().filter(command -> "sendMaterials".equals(command.id)).findFirst().orElseThrow()
                .name = "导出物料";
        assertTrue(HelpCardRenderer.text(config).contains("/导出物料123："));
    }

    @Test
    void createsReadablePngHelpCard() throws Exception {
        Path card = HelpCardRenderer.create(temporaryDirectory);

        assertTrue(ImageIO.read(card.toFile()).getWidth() >= 1100);
        assertTrue(ImageIO.read(card.toFile()).getHeight() > 600);
    }
}
