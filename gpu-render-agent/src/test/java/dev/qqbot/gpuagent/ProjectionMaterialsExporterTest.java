package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class ProjectionMaterialsExporterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void exportsExcelWorkbookWithSideBySideProjectionAndContainerTables() throws Exception {
        var report = new LitematicMaterials.Report(
                List.of(new LitematicMaterials.Material("minecraft:stone", 12)),
                List.of(new LitematicMaterials.Material("minecraft:diamond", 3)));
        Path runtimeRoot = temporaryDirectory.resolve("agent-home");
        installChineseNames(runtimeRoot);

        Path output = ProjectionMaterialsExporter.create("机器:甲.litematic", report,
                temporaryDirectory.resolve("out"), runtimeRoot);
        String text = worksheet(output);

        assertEquals("机器_甲-materials.xlsx", output.getFileName().toString());
        assertTrue(text.contains(">投影材料列表<"));
        assertTrue(text.contains(">投影容器列表<"));
        assertTrue(text.contains(">物品名称<"));
        assertTrue(text.contains(">容器物品ID<"));
        assertTrue(text.contains(">石头<"));
        assertTrue(text.contains(">stone<"));
        assertTrue(text.contains(">12<"));
        assertTrue(text.contains(">容器内材料（汇总）<"));
        assertTrue(text.contains(">钻石<"));
        assertTrue(text.contains(">diamond<"));
        assertFalse(text.contains("Missing"));
    }

    @Test
    void safelyNamesWorkbookAndKeepsHeadersWhenBothSectionsAreEmpty() throws Exception {
        var report = new LitematicMaterials.Report(List.of(), List.of());
        Path output = ProjectionMaterialsExporter.create("bad/name?.litematic", report, temporaryDirectory);
        String text = worksheet(output);

        assertEquals("bad_name_-materials.xlsx", output.getFileName().toString());
        assertTrue(text.contains(">投影材料列表<"));
        assertTrue(text.contains(">投影容器列表<"));
        assertFalse(text.contains("null"));
    }

    @Test
    void includesProjectionFileMetadataAboveTheMaterialsTable() throws Exception {
        Path projection = temporaryDirectory.resolve("铁合块.litematic");
        try (var bytes = new ByteArrayOutputStream();
             var gzip = new GZIPOutputStream(bytes);
             var nbt = new DataOutputStream(gzip)) {
            nbt.writeByte(10); nbt.writeUTF("");
            writeInt(nbt, "Version", 7);
            writeInt(nbt, "MinecraftDataVersion", 3953);
            nbt.writeByte(10); nbt.writeUTF("Metadata");
            nbt.writeByte(8); nbt.writeUTF("Author"); nbt.writeUTF("half_kite");
            writeLong(nbt, "TimeCreated", 1730902786L);
            writeLong(nbt, "TotalBlocks", 75);
            writeLong(nbt, "TotalVolume", 105);
            nbt.writeByte(10); nbt.writeUTF("EnclosingSize");
            writeInt(nbt, "x", 3); writeInt(nbt, "y", 5); writeInt(nbt, "z", 7);
            nbt.writeByte(0); nbt.writeByte(0); nbt.writeByte(0);
            nbt.close();
            Files.write(projection, bytes.toByteArray());
        }
        var report = new LitematicMaterials.Report(
                List.of(new LitematicMaterials.Material("minecraft:stone", 75)), List.of());
        Path output = ProjectionMaterialsExporter.create("铁合块.litematic", report,
                temporaryDirectory.resolve("output"), null, projection);
        String sheet = worksheet(output);
        assertTrue(sheet.contains(">铁合块.litematic<"));
        assertTrue(sheet.contains(">half_kite<"));
        assertTrue(sheet.contains(">2024-11-06 22:19:46<"));
        assertTrue(sheet.contains(">75<"));
        assertTrue(sheet.contains(">105<"));
        assertTrue(sheet.contains(">3 × 5 × 7<"));
        assertTrue(sheet.contains(">Litematic 版本<"));
        assertTrue(sheet.contains(">1.21<"));
        assertTrue(sheet.contains(">3953<"));
        assertTrue(sheet.contains(">投影材料种类<"));
        assertTrue(sheet.contains(">容器内材料种类<"));
    }

    private static String worksheet(Path workbook) throws Exception {
        try (ZipFile zip = new ZipFile(workbook.toFile(), StandardCharsets.UTF_8)) {
            return new String(zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).readAllBytes(),
                    StandardCharsets.UTF_8);
        }
    }

    private static void writeInt(DataOutputStream output, String name, int value) throws Exception {
        output.writeByte(3); output.writeUTF(name); output.writeInt(value);
    }

    private static void writeLong(DataOutputStream output, String name, long value) throws Exception {
        output.writeByte(4); output.writeUTF(name); output.writeLong(value);
    }

    private static void installChineseNames(Path applicationRoot) throws Exception {
        String hash = "ab".repeat(20);
        Path index = applicationRoot.resolve("runtime/assets/indexes/26.3.json");
        Path object = applicationRoot.resolve("runtime/assets/objects/ab").resolve(hash);
        Files.createDirectories(index.getParent());
        Files.createDirectories(object.getParent());
        Files.writeString(index, """
                {"objects":{"minecraft/lang/zh_cn.json":{"hash":"%s"}}}
                """.formatted(hash));
        Files.writeString(object, """
                {"block.minecraft.stone":"石头","item.minecraft.diamond":"钻石"}
                """);
    }
}
