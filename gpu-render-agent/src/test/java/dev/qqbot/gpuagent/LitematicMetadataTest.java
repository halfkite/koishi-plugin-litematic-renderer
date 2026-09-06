package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class LitematicMetadataTest {
    @Test
    void readsGzipMetadataAndFormatsProjectionInformation() throws Exception {
        byte[] data = sample("半筝", 75, 105, 3, 5, 7);
        LitematicMetadata.Values values = LitematicMetadata.parse(data);

        assertEquals("半筝", values.author());
        assertEquals(75L, values.totalBlocks());
        assertEquals(105L, values.totalVolume());
        assertArrayEquals(new int[] {3, 5, 7}, values.size());
        assertEquals(7L, values.litematicVersion());
        assertEquals(3953L, values.minecraftDataVersion());
        assertEquals("1.21", values.minecraftVersion());
        assertEquals("2024-11-06 22:19:46", values.createdAt());

        AgentConfig config = new AgentConfig();
        String text = LitematicMetadata.format(data, "铁合块.litematic", config);
        assertEquals("投影名称：铁合块\n保存者游戏 ID：半筝\n创建时间：2024-11-06 22:19:46\n"
                        + "方块数/体积：75/105\n尺寸：3 × 5 × 7\nLitematic 版本：7\n游戏版本：1.21（数据版本：3953）", text);
    }

    @Test
    void eachMetadataFieldCanBeHiddenWithoutAffectingTheOthers() throws Exception {
        AgentConfig config = new AgentConfig();
        config.showMetadataAuthor = false;
        config.showMetadataSize = false;
        String text = LitematicMetadata.format(sample("作者", 1, 2, 3, 4, 5), "demo.litematic", config);
        assertTrue(text.contains("投影名称：demo"));
        assertFalse(text.contains("保存者游戏 ID"));
        assertFalse(text.contains("尺寸"));
        assertTrue(text.contains("方块数/体积：1/2"));
        assertTrue(text.contains("游戏版本：1.21（数据版本：3953）"));
    }

    @Test
    void compactFormatUsesTheFourLineProjectionSummary() throws Exception {
        AgentConfig config = new AgentConfig();
        config.metadataFormat = "compact";
        String text = LitematicMetadata.format(sample("half_kite", 72, 210, 7, 6, 5), "门2.litematic", config);
        assertEquals("门2\n[half_kite]于2024-11-06 22:19:46 保存\n方块数72-体积210-尺寸7×6×5\n投影版本7-游戏版本1.21-数据版本3953", text);
    }

    private static byte[] sample(String author, int blocks, int volume, int x, int y, int z) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed);
             DataOutputStream output = new DataOutputStream(gzip)) {
            output.writeByte(10); writeString(output, "");
            tagInt(output, "Version", 7);
            tagInt(output, "MinecraftDataVersion", 3953);
            output.writeByte(10); writeString(output, "Metadata");
            tagString(output, "Author", author);
            tagLong(output, "TimeCreated", 1730902786L);
            tagLong(output, "TotalBlocks", blocks);
            tagLong(output, "TotalVolume", volume);
            output.writeByte(10); writeString(output, "EnclosingSize");
            tagInt(output, "x", x); tagInt(output, "y", y); tagInt(output, "z", z); output.writeByte(0);
            output.writeByte(0);
            output.writeByte(0);
        }
        return compressed.toByteArray();
    }

    private static void tagString(DataOutputStream output, String name, String value) throws IOException {
        output.writeByte(8); writeString(output, name); writeString(output, value);
    }

    private static void tagInt(DataOutputStream output, String name, int value) throws IOException {
        output.writeByte(3); writeString(output, name); output.writeInt(value);
    }

    private static void tagLong(DataOutputStream output, String name, long value) throws IOException {
        output.writeByte(4); writeString(output, name); output.writeLong(value);
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeShort(bytes.length); output.write(bytes);
    }
}
