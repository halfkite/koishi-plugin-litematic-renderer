package dev.qqbot.standalone;

import dev.qqbot.standalone.Geometry.BakedModel;
import dev.qqbot.standalone.Geometry.Quad;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class RendererSmokeTest {
    private RendererSmokeTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected bundled resource pack path");
        try (ResourcePacks resources = new ResourcePacks(Path.of(args[0]), List.of())) {
            ModelResolver models = new ModelResolver(resources);
            models.resolve(state("minecraft:not_a_real_block", Map.of()));
            require(!models.diagnostics().isEmpty(), "missing block diagnostic was not recorded");
            BakedModel verticalChain = models.resolve(state("minecraft:chain", Map.of("axis", "y", "waterlogged", "false")));
            BakedModel horizontalChain = models.resolve(state("minecraft:chain", Map.of("axis", "x", "waterlogged", "false")));
            require(verticalChain.quads().size() == 4 && horizontalChain.quads().size() == 4, "chain model was not rendered");
            require(verticalChain.quads().stream().noneMatch(quad -> quad.texture() == resources.missingTexture()), "chain used the missing texture");
            EntityModelResolver entityModels = new EntityModelResolver(resources, models);
            Litematic.Entity itemFrame = new Litematic.Entity("minecraft:item_frame", 0.5, 0.5, 0.5, 0, 0,
                Map.of("Facing", 3, "Item", Map.of("id", "minecraft:diamond", "Count", 1)));
            require(entityModels.resolve(itemFrame).quads().size() > 6, "item frame and contained item were not rendered");
            require(Litematic.isContainerBlockEntity("minecraft:chest"), "chest container was not filtered");
            require(Litematic.isContainerBlockEntity("minecraft:shulker_box"), "shulker container was not filtered");
            require(!Litematic.isContainerBlockEntity("minecraft:banner"), "banner data must remain available for rendering");

            BakedModel playerHead = models.resolve(
                state("minecraft:player_head", Map.of("rotation", "4")),
                blockEntity("minecraft:skull", Map.of()));
            require(playerHead.quads().size() == 12, "player head base and overlay were not rendered");

            BakedModel wallHead = models.resolve(
                state("minecraft:wither_skeleton_wall_skull", Map.of("facing", "east")),
                blockEntity("minecraft:skull", Map.of()));
            require(wallHead.quads().size() == 6, "wall skull was not rendered");
            require(wallHead.quads().stream().noneMatch(quad -> quad.texture() == resources.missingTexture()),
                "wall skull used the missing texture");

            Litematic.BlockState bannerState = state("minecraft:white_banner", Map.of("rotation", "2"));
            BakedModel plainBanner = models.resolve(bannerState, blockEntity("minecraft:banner", Map.of()));
            BakedModel patternedBanner = models.resolve(bannerState, blockEntity("minecraft:banner", Map.of(
                "patterns", List.of(Map.of("pattern", "minecraft:creeper", "color", "red")))));
            require(patternedBanner.quads().size() == 18, "standing banner cloth, bar, and pole were not rendered");
            require(imageHash(firstTexture(plainBanner)) != imageHash(firstTexture(patternedBanner)),
                "banner pattern did not change the composed texture");

            BakedModel wallBanner = models.resolve(
                state("minecraft:blue_wall_banner", Map.of("facing", "south")),
                blockEntity("minecraft:banner", Map.of("Patterns", List.of(Map.of("Pattern", "bo", "Color", 15)))));
            require(wallBanner.quads().size() == 12, "wall banner cloth and bar were not rendered");

            BakedModel grass = models.resolve(state("minecraft:grass", Map.of()));
            require(!grass.quads().isEmpty(), "legacy grass was not mapped to short_grass");
            BakedModel structureVoid = models.resolve(state("minecraft:structure_void", Map.of()));
            require(structureVoid.quads().isEmpty(), "structure void must not render as a solid cube");
            BakedModel decoratedPot = models.resolve(state("minecraft:decorated_pot", Map.of()));
            require(!decoratedPot.quads().isEmpty(), "decorated pot model was not rendered");

            Litematic.BlockState playerState = state("minecraft:player_head", Map.of("rotation", "4"));
            Litematic.BlockState bannerBlockState = state("minecraft:white_banner", Map.of("rotation", "2"));
            Litematic.BlockState skullState = state("minecraft:wither_skeleton_wall_skull", Map.of("facing", "east"));
            Litematic schematic = new Litematic(
                List.of(
                    new Litematic.Block(0, 0, 0, playerState),
                    new Litematic.Block(1, 0, 0, bannerBlockState),
                    new Litematic.Block(2, 0, 0, skullState)),
                List.of(
                    new Litematic.BlockEntity(0, 0, 0, "minecraft:skull", Map.of()),
                    new Litematic.BlockEntity(1, 0, 0, "minecraft:banner", Map.of(
                        "patterns", List.of(Map.of("pattern", "minecraft:creeper", "color", "red")))),
                    new Litematic.BlockEntity(2, 0, 0, "minecraft:skull", Map.of())),
                List.of(), new Litematic.Bounds(0, 0, 0, 2, 1, 0));
            SoftwareRenderer renderer = new SoftwareRenderer(schematic, models, entityModels);
            Path output = Files.createTempFile("litematic-head-banner-smoke-", ".png");
            Path sixFaceOutput = Files.createTempFile("litematic-six-face-smoke-", ".png");
            Path verticalOutput = Files.createTempFile("litematic-six-face-vertical-smoke-", ".png");
            try {
                SoftwareRenderer.Settings settings = new SoftwareRenderer.Settings(256, 1, 135, 36, 0.78, "#000000", true);
                renderer.render(settings, 135, output);
                BufferedImage rendered = javax.imageio.ImageIO.read(output.toFile());
                require(rendered != null && rendered.getWidth() == 256 && rendered.getHeight() == 256,
                    "head and banner integration render did not create a valid PNG");
                require(opaquePixels(rendered) > 100, "head and banner integration render was empty");

                renderer.renderSixFaces(settings, 300, "horizontal", sixFaceOutput);
                BufferedImage sixFaces = javax.imageio.ImageIO.read(sixFaceOutput.toFile());
                require(sixFaces != null && sixFaces.getWidth() == 300 && sixFaces.getHeight() == 200,
                    "horizontal six-face render did not use the requested 3x2 dimensions");
                require(opaquePixels(sixFaces) > 500, "horizontal six-face material render was empty");
                requireWhitePixel(sixFaces, 7, 19, "up label");
                requireWhitePixel(sixFaces, 105, 8, "down label");
                requireWhitePixel(sixFaces, 204, 9, "east label");
                requireWhitePixel(sixFaces, 7, 114, "south label");
                requireWhitePixel(sixFaces, 106, 116, "west label");
                requireWhitePixel(sixFaces, 207, 117, "north label");

                renderer.renderSixFaces(settings, 300, "vertical", verticalOutput);
                BufferedImage vertical = javax.imageio.ImageIO.read(verticalOutput.toFile());
                require(vertical != null && vertical.getWidth() == 200 && vertical.getHeight() == 300,
                    "vertical six-face render did not use the requested 2x3 dimensions");
                require(opaquePixels(vertical) > 500, "vertical six-face material render was empty");
            } finally {
                Files.deleteIfExists(output);
                Files.deleteIfExists(sixFaceOutput);
                Files.deleteIfExists(verticalOutput);
            }
        }
        System.out.println("Renderer smoke test passed: heads and banners");
    }

    private static void requireWhitePixel(BufferedImage image, int x, int y, String label) {
        require((image.getRGB(x, y) & 0xffffffffL) == 0xffffffffL, label + " bitmap stroke is missing");
    }

    private static Litematic.BlockState state(String name, Map<String, String> properties) {
        return new Litematic.BlockState(name, properties);
    }

    private static Litematic.BlockEntity blockEntity(String id, Map<String, Object> data) {
        return new Litematic.BlockEntity(0, 0, 0, id, data);
    }

    private static BufferedImage firstTexture(BakedModel model) {
        return model.quads().stream().findFirst().map(Quad::texture).orElseThrow();
    }

    private static int imageHash(BufferedImage image) {
        int hash = 1;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            hash = 31 * hash + image.getRGB(x, y);
        }
        return hash;
    }

    private static int opaquePixels(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            if ((image.getRGB(x, y) >>> 24) != 0) count++;
        }
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
