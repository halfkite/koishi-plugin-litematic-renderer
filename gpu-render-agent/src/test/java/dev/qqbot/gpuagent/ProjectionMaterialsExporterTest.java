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
        assertTrue(text.contains(">钻石<"));
        assertTrue(text.contains(">diamond<"));
        assertFalse(text.contains(">容器名称<"));
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
        assertFalse(text.contains("含水方块数量"));
        assertFalse(text.contains("含岩浆炼药锅数量"));
        assertFalse(text.contains("null"));
    }

    @Test
    void formatsItemEquivalentCountsAndOptionalWaterAndLavaSummariesLikeTheReference() throws Exception {
        var report = new LitematicMaterials.Report(
                List.of(
                        new LitematicMaterials.Material("minecraft:redstone_torch", 119),
                        new LitematicMaterials.Material("minecraft:water_bucket", 70),
                        new LitematicMaterials.Material("minecraft:cauldron", 4)),
                List.of(),
                List.of(new LitematicMaterials.Material("minecraft:oak_stairs", 2)),
                70,
                4);
        Path output = ProjectionMaterialsExporter.create("48w刷花收集.litematic", report, temporaryDirectory);
        String sheet = worksheet(output);
        String styles = workbookEntry(output, "xl/styles.xml");

        assertTrue(sheet.contains(">含水方块数量<"));
        assertTrue(sheet.contains(">72（oak stairs × 2、水方块 × 70）<"));
        assertTrue(sheet.contains(">含岩浆炼药锅数量<"));
        assertTrue(sheet.contains(">4（炼药锅）<"));
        assertTrue(sheet.contains(">redstone_torch<"));
        assertTrue(sheet.contains(">water_bucket<"));
        assertTrue(sheet.contains(">cauldron<"));
        assertFalse(sheet.contains("redstone_wall_torch"));
        assertFalse(sheet.contains("lava_cauldron"));
        assertTrue(styles.contains("<name val=\"微软雅黑\"/>"));
        assertTrue(styles.contains("<sz val=\"11\"/>"));
        assertTrue(sheet.contains("<dimension ref=\"A1:I"));
        assertTrue(sheet.contains("<col min=\"1\" max=\"1\" width=\"15.625\""));
        assertTrue(sheet.contains("<col min=\"2\" max=\"2\" width=\"18.625\""));
        assertTrue(sheet.contains("<col min=\"6\" max=\"6\" width=\"15.625\""));
        assertTrue(sheet.contains("<col min=\"7\" max=\"7\" width=\"18.625\""));
        assertTrue(sheet.contains("<mergeCell ref=\"B1:I1\"/>"));
    }

    @Test
    void exportsRenamedIronChainAsOneRowWithNewAndOldCounts() throws Exception {
        var report = new LitematicMaterials.Report(
                List.of(new LitematicMaterials.Material("minecraft:iron_chain", 3)),
                List.of(new LitematicMaterials.Material("minecraft:iron_chain", 5)),
                List.of(), 0, 0, List.of(), 1, 2, 2, 3);

        String sheet = worksheet(ProjectionMaterialsExporter.create("chains", report, temporaryDirectory));

        assertTrue(sheet.contains(">铁链/锁链<"));
        assertTrue(sheet.contains(">iron_chain/chain<"));
        assertTrue(sheet.contains(">2/1<"));
        assertTrue(sheet.contains(">3/2<"));
        assertTrue(sheet.contains(">0.1/0.1<"));
    }

    @Test
    void keepsBlocksWithoutAnItemOutOfTheMaterialsAndLabelsThemSeparately() throws Exception {
        var report = new LitematicMaterials.Report(
                List.of(new LitematicMaterials.Material("minecraft:stone", 1)), List.of(), List.of(), 0, 0,
                List.of(new LitematicMaterials.Material("minecraft:fire", 3)));
        String sheet = worksheet(ProjectionMaterialsExporter.create("technical-blocks", report, temporaryDirectory));

        assertTrue(sheet.contains(">未计入材料的无物品方块<"));
        assertTrue(sheet.contains(">fire（minecraft:fire）× 3<"));
        assertTrue(sheet.contains(">stone<"));
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
        return workbookEntry(workbook, "xl/worksheets/sheet1.xml");
    }

    private static String workbookEntry(Path workbook, String entry) throws Exception {
        try (ZipFile zip = new ZipFile(workbook.toFile(), StandardCharsets.UTF_8)) {
            return new String(zip.getInputStream(zip.getEntry(entry)).readAllBytes(),
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
