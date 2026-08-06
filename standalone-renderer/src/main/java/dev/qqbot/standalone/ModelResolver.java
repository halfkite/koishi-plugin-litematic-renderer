package dev.qqbot.standalone;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.qqbot.standalone.Geometry.BakedModel;
import dev.qqbot.standalone.Geometry.Direction;
import dev.qqbot.standalone.Geometry.Quad;
import dev.qqbot.standalone.Geometry.Vec3;
import dev.qqbot.standalone.Geometry.Vertex;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

final class ModelResolver {
    record Diagnostic(String state, String reason, int count) {}
    private static final class DiagnosticCounter { int count; }
    private record ModelRef(String id, int xRotation, int yRotation, boolean uvLock) {}
    private record ModelData(Map<String, String> textures, JsonArray elements) {}

    private final ResourcePacks resources;
    private final Map<Litematic.BlockState, BakedModel> bakedStates = new ConcurrentHashMap<>();
    private final Map<String, ModelData> models = new ConcurrentHashMap<>();
    private final Map<String, BufferedImage> processedTextures = new ConcurrentHashMap<>();
    private final Map<String, Optional<BufferedImage>> remoteTextures = new ConcurrentHashMap<>();
    private final Map<String, DiagnosticCounter> diagnostics = new ConcurrentHashMap<>();

    ModelResolver(ResourcePacks resources) {
        this.resources = resources;
    }

    BakedModel resolve(Litematic.BlockState state) {
        return bakedStates.computeIfAbsent(state, this::bakeState);
    }

    List<Diagnostic> diagnostics() {
        List<Diagnostic> result = new ArrayList<>();
        diagnostics.forEach((key, value) -> {
            int separator = key.indexOf('\u0000');
            result.add(new Diagnostic(key.substring(0, separator), key.substring(separator + 1), value.count));
        });
        result.sort(java.util.Comparator.comparing(Diagnostic::state).thenComparing(Diagnostic::reason));
        return List.copyOf(result);
    }

    private void diagnostic(Litematic.BlockState state, String reason) {
        String properties = state.properties().entrySet().stream().sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        diagnostic(state.name() + (state.properties().isEmpty() ? "" : properties), reason);
    }

    private void diagnostic(String state, String reason) {
        diagnostics.computeIfAbsent(state + '\u0000' + reason, ignored -> new DiagnosticCounter()).count++;
    }

    BakedModel resolve(Litematic.BlockState state, Litematic.BlockEntity blockEntity) {
        String path = Identifier.parse(state.name()).path;
        if (isHead(path)) return headModel(state, blockEntity);
        if (path.endsWith("_banner")) return bannerModel(state, blockEntity);
        return resolve(state);
    }

    boolean isOpaque(Litematic.BlockState state) {
        String name = state.name();
        String[] transparent = {
            "air", "glass", "water", "lava", "leaves", "wire", "torch", "rail", "flower", "sapling",
            "grass", "vine", "mushroom", "carpet", "slab", "stairs", "fence", "wall", "pane", "door",
            "trapdoor", "button", "lever", "pressure_plate", "repeater", "comparator", "hopper", "chest",
            "shulker", "sign", "banner", "chain", "lantern", "scaffolding", "candle", "rod", "anvil",
            "piston_head", "moving_piston", "end_portal", "nether_portal", "snow", "bed", "cauldron"
        };
        for (String token : transparent) if (name.contains(token)) return false;
        return true;
    }

    boolean hidesNeighborFace(Litematic.BlockState state, Litematic.BlockState neighbor) {
        if (isOpaque(neighbor)) return true;
        String name = state.name();
        String neighborName = neighbor.name();
        if (name.equals(neighborName) && isSelfCullingTranslucent(name)) return true;
        return isWater(name) && isWater(neighborName) || isLava(name) && isLava(neighborName);
    }

    boolean isTranslucent(Litematic.BlockState state) {
        String path = Identifier.parse(state.name()).path;
        return path.contains("glass") || path.equals("water") || path.equals("bubble_column")
            || path.equals("ice") || path.equals("frosted_ice") || path.equals("slime_block")
            || path.equals("honey_block") || path.endsWith("_portal") || path.equals("beacon");
    }

    private BakedModel bakeState(Litematic.BlockState state) {
        BakedModel entityModel = entityBlockModel(state);
        if (entityModel != null) return entityModel;
        List<ModelRef> references = blockStateModels(state);
        List<Quad> quads = new ArrayList<>();
        for (ModelRef reference : references) quads.addAll(bakeModel(reference, state));
        if (quads.isEmpty() && Identifier.parse(state.name()).path.equals("cobblestone_wall")) return new BakedModel(List.of());
        if (quads.isEmpty()) {
            diagnostic(state, references.isEmpty() ? "missing blockstate/model; fallback cube" : "model produced no faces; fallback cube");
            quads.addAll(fallbackCube(state));
        }
        return new BakedModel(List.copyOf(quads));
    }

    private BakedModel entityBlockModel(Litematic.BlockState state) {
        String path = Identifier.parse(state.name()).path;
        if (path.equals("structure_void") || path.equals("light") || path.equals("end_portal")) return new BakedModel(List.of());
        if (path.equals("conduit")) {
            BufferedImage texture = resources.texture("minecraft:block/conduit");
            Map<Direction, BufferedImage> faces = new HashMap<>();
            for (Direction direction : Direction.values()) faces.put(direction, texture);
            return new BakedModel(List.copyOf(cuboid(new double[]{1, 1, 1}, new double[]{15, 15, 15}, faces, Set.of(), 0, 0)));
        }
        if (path.equals("water") || path.equals("bubble_column")) return fluidModel(state, true);
        if (path.equals("lava")) return fluidModel(state, false);
        if (isHead(path)) return headModel(state, null);
        if (path.equals("chest") || path.equals("trapped_chest") || path.equals("ender_chest") || path.endsWith("copper_chest")) {
            return chestModel(state, path);
        }
        if (path.equals("shulker_box") || path.endsWith("_shulker_box")) return shulkerModel(state, path);
        if (path.equals("decorated_pot")) return decoratedPotModel(state);
        if ((path.endsWith("_sign") || path.endsWith("_wall_sign")) && !hasModernSignModel(path)) return signModel(state, path);
        return null;
    }

    private BakedModel decoratedPotModel(Litematic.BlockState state) {
        BufferedImage base = resources.texture("minecraft:entity/decorated_pot/decorated_pot_base");
        BufferedImage side = resources.texture("minecraft:entity/decorated_pot/decorated_pot_side");
        Map<Direction, BufferedImage> sideTextures = new HashMap<>();
        for (Direction direction : Direction.values()) sideTextures.put(direction, side);
        sideTextures.put(Direction.UP, base);
        sideTextures.put(Direction.DOWN, base);
        // The vanilla pot is tapered; four narrow cuboids preserve its silhouette
        // while keeping the entity texture mapping stable across resource-pack sizes.
        List<Quad> quads = new ArrayList<>();
        quads.addAll(cuboid(new double[]{3, 0, 3}, new double[]{13, 2, 13}, sideTextures, Set.of(Direction.UP), 0, 0));
        quads.addAll(cuboid(new double[]{2, 2, 2}, new double[]{14, 14, 14}, sideTextures, Set.of(Direction.UP, Direction.DOWN), 0, 0));
        quads.addAll(cuboid(new double[]{3, 14, 3}, new double[]{13, 16, 13}, sideTextures, Set.of(Direction.DOWN), 0, 0));
        return new BakedModel(List.copyOf(quads));
    }

    private BakedModel headModel(Litematic.BlockState state, Litematic.BlockEntity blockEntity) {
        String path = Identifier.parse(state.name()).path;
        boolean player = path.startsWith("player_");
        BufferedImage skin = player ? playerSkin(blockEntity) : headTexture(path);
        int scale = Math.max(1, skin.getWidth() / 64);
        Map<Direction, BufferedImage> base = Map.of(
            Direction.UP, crop(skin, scale, 8, 0, 8, 8, false, false),
            Direction.DOWN, crop(skin, scale, 16, 0, 8, 8, false, false),
            Direction.WEST, crop(skin, scale, 0, 8, 8, 8, false, false),
            Direction.SOUTH, crop(skin, scale, 8, 8, 8, 8, false, false),
            Direction.EAST, crop(skin, scale, 16, 8, 8, 8, false, false),
            Direction.NORTH, crop(skin, scale, 24, 8, 8, 8, false, false));
        boolean wall = path.contains("_wall_");
        double yRotation = wall ? chestRotation(state.properties().getOrDefault("facing", "north")) : -rotation(state) * 22.5;
        double[] from = wall ? new double[]{4, 4, 8} : new double[]{4, 0, 4};
        double[] to = wall ? new double[]{12, 12, 16} : new double[]{12, 8, 12};
        List<Quad> quads = new ArrayList<>(cuboid(from, to, base, Set.of(), 0, yRotation));
        if (player) {
            Map<Direction, BufferedImage> overlay = Map.of(
                Direction.UP, crop(skin, scale, 40, 0, 8, 8, false, false),
                Direction.DOWN, crop(skin, scale, 48, 0, 8, 8, false, false),
                Direction.WEST, crop(skin, scale, 32, 8, 8, 8, false, false),
                Direction.SOUTH, crop(skin, scale, 40, 8, 8, 8, false, false),
                Direction.EAST, crop(skin, scale, 48, 8, 8, 8, false, false),
                Direction.NORTH, crop(skin, scale, 56, 8, 8, 8, false, false));
            double[] overlayFrom = {from[0] - 0.25, from[1] - 0.25, from[2] - 0.25};
            double[] overlayTo = {to[0] + 0.25, to[1] + 0.25, to[2] + 0.25};
            quads.addAll(cuboid(overlayFrom, overlayTo, overlay, Set.of(), 0, yRotation));
        }
        return new BakedModel(List.copyOf(quads));
    }

    private static boolean isHead(String path) {
        return path.endsWith("_head") || path.endsWith("_wall_head") || path.endsWith("_skull") || path.endsWith("_wall_skull");
    }

    private static int rotation(Litematic.BlockState state) {
        try { return Integer.parseInt(state.properties().getOrDefault("rotation", "0")); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private BufferedImage headTexture(String path) {
        if (path.startsWith("wither_skeleton_")) return resources.texture("minecraft:entity/skeleton/wither_skeleton");
        if (path.startsWith("skeleton_")) return resources.texture("minecraft:entity/skeleton/skeleton");
        if (path.startsWith("zombie_")) return resources.texture("minecraft:entity/zombie/zombie");
        if (path.startsWith("creeper_")) return resources.texture("minecraft:entity/creeper/creeper");
        if (path.startsWith("piglin_")) return resources.texture("minecraft:entity/piglin/piglin");
        if (path.startsWith("dragon_")) return resources.texture("minecraft:entity/enderdragon/dragon");
        return resources.firstTexture("minecraft:entity/player/wide/steve", "minecraft:entity/player/slim/steve");
    }

    private BufferedImage playerSkin(Litematic.BlockEntity blockEntity) {
        String url = blockEntity == null ? null : skinUrl(blockEntity.data());
        if (url != null) {
            Optional<BufferedImage> loaded = remoteTextures.computeIfAbsent(url, this::downloadSkin);
            if (loaded.isPresent()) return loaded.get();
        }
        return resources.firstTexture("minecraft:entity/player/wide/steve", "minecraft:entity/player/slim/steve");
    }

    private Optional<BufferedImage> downloadSkin(String value) {
        try {
            URI uri = URI.create(value);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || !"textures.minecraft.net".equalsIgnoreCase(uri.getHost())) {
                System.err.println("Ignored untrusted player head texture URL: " + value);
                return Optional.empty();
            }
            if ("http".equalsIgnoreCase(uri.getScheme())) uri = URI.create(value.replaceFirst("(?i)^http://", "https://"));
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body().length > 4 * 1024 * 1024) return Optional.empty();
            return Optional.ofNullable(javax.imageio.ImageIO.read(new ByteArrayInputStream(response.body())));
        } catch (Exception exception) {
            System.err.println("Unable to load player head texture: " + exception.getMessage());
            return Optional.empty();
        }
    }

    private static String skinUrl(Map<String, Object> data) {
        Map<String, Object> profile = nbtCompound(first(data, "profile", "SkullOwner", "Owner"));
        if (profile == null) return null;
        Object properties = first(profile, "properties", "Properties");
        String encoded = null;
        Map<String, Object> propertyMap = nbtCompound(properties);
        if (propertyMap != null) encoded = textureValue(first(propertyMap, "textures", "Textures"));
        if (encoded == null && properties instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> property = nbtCompound(item);
                if (property != null && "textures".equalsIgnoreCase(String.valueOf(first(property, "name", "Name")))) {
                    encoded = string(first(property, "value", "Value"));
                    if (encoded != null) break;
                }
            }
        }
        if (encoded == null) return null;
        try {
            String json = new String(Base64.getDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject textures = object(root.get("textures"));
            JsonObject skin = textures == null ? null : object(textures.get("SKIN"));
            return skin != null && skin.has("url") ? skin.get("url").getAsString() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String textureValue(Object value) {
        if (value instanceof String string) return string;
        if (value instanceof List<?> list && !list.isEmpty()) {
            Map<String, Object> item = nbtCompound(list.get(0));
            return item == null ? null : string(first(item, "value", "Value"));
        }
        return null;
    }

    private BakedModel bannerModel(Litematic.BlockState state, Litematic.BlockEntity blockEntity) {
        String path = Identifier.parse(state.name()).path;
        boolean wall = path.endsWith("_wall_banner");
        double yRotation = wall ? chestRotation(state.properties().getOrDefault("facing", "north")) : -rotation(state) * 22.5;
        BufferedImage cloth = bannerTexture(path, blockEntity);
        Map<Direction, BufferedImage> clothFaces = new HashMap<>();
        for (Direction direction : Direction.values()) clothFaces.put(direction, cloth);
        double[] from = wall ? new double[]{1, 1, 14.5} : new double[]{1, 1, 7.5};
        double[] to = wall ? new double[]{15, 15, 15.5} : new double[]{15, 15, 8.5};
        List<Quad> quads = new ArrayList<>(cuboid(from, to, clothFaces, Set.of(), 0, yRotation));

        BufferedImage wood = resources.texture("minecraft:block/oak_planks");
        Map<Direction, BufferedImage> woodFaces = new HashMap<>();
        for (Direction direction : Direction.values()) woodFaces.put(direction, wood);
        quads.addAll(cuboid(
            wall ? new double[]{1, 14, 14} : new double[]{1, 14, 7},
            wall ? new double[]{15, 15, 16} : new double[]{15, 15, 9},
            woodFaces, Set.of(), 0, yRotation));
        if (!wall) quads.addAll(cuboid(new double[]{7.5, 0, 7.5}, new double[]{8.5, 16, 8.5}, woodFaces, Set.of(), 0, yRotation));
        return new BakedModel(List.copyOf(quads));
    }

    private BufferedImage bannerTexture(String blockPath, Litematic.BlockEntity blockEntity) {
        String baseName = blockPath.replace("_wall_banner", "").replace("_banner", "");
        int baseColor = dyeColor(baseName);
        if (blockEntity != null) {
            Object legacyBase = first(blockEntity.data(), "Base", "base");
            if (legacyBase instanceof Number number) baseColor = dyeColor(number.intValue());
        }
        BufferedImage result = tintMask(resources.texture("minecraft:entity/banner/base"), baseColor);
        List<?> patterns = blockEntity == null ? null : nbtList(first(blockEntity.data(), "patterns", "Patterns"));
        if (patterns == null) return result;

        for (Object value : patterns) {
            Map<String, Object> pattern = nbtCompound(value);
            if (pattern == null) continue;
            String id = string(first(pattern, "pattern", "Pattern"));
            String texture = bannerPattern(id);
            if (texture == null || !resources.hasTexture("minecraft:entity/banner/" + texture)) continue;
            Object colorValue = first(pattern, "color", "Color");
            int color = colorValue instanceof Number number ? dyeColor(number.intValue()) : dyeColor(String.valueOf(colorValue));
            BufferedImage layer = tintMask(resources.texture("minecraft:entity/banner/" + texture), color);
            java.awt.Graphics2D graphics = result.createGraphics();
            graphics.drawImage(layer, 0, 0, result.getWidth(), result.getHeight(), null);
            graphics.dispose();
        }
        return result;
    }

    private static BufferedImage tintMask(BufferedImage source, int color) {
        BufferedImage target = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int red = color >> 16 & 255, green = color >> 8 & 255, blue = color & 255;
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++) {
            int pixel = source.getRGB(x, y);
            int alpha = pixel >>> 24;
            int luminance = ((pixel >> 16 & 255) + (pixel >> 8 & 255) + (pixel & 255)) / 3;
            target.setRGB(x, y, alpha << 24 | red * luminance / 255 << 16 | green * luminance / 255 << 8 | blue * luminance / 255);
        }
        return target;
    }

    private static int dyeColor(String name) {
        if (name == null) return 0xf9fffe;
        String normalized = name.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        return switch (normalized) {
            case "orange" -> 0xf9801d;
            case "magenta" -> 0xc74ebd;
            case "light_blue" -> 0x3ab3da;
            case "yellow" -> 0xfed83d;
            case "lime" -> 0x80c71f;
            case "pink" -> 0xf38baa;
            case "gray" -> 0x474f52;
            case "light_gray", "silver" -> 0x9d9d97;
            case "cyan" -> 0x169c9c;
            case "purple" -> 0x8932b8;
            case "blue" -> 0x3c44aa;
            case "brown" -> 0x835432;
            case "green" -> 0x5e7c16;
            case "red" -> 0xb02e26;
            case "black" -> 0x1d1d21;
            default -> 0xf9fffe;
        };
    }

    private static int dyeColor(int id) {
        String[] names = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
        return dyeColor(names[Math.max(0, Math.min(names.length - 1, id))]);
    }

    private static String bannerPattern(String id) {
        if (id == null) return null;
        String path = id.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        return switch (path) {
            case "bs" -> "stripe_bottom";
            case "ts" -> "stripe_top";
            case "ls" -> "stripe_left";
            case "rs" -> "stripe_right";
            case "cs" -> "stripe_center";
            case "ms" -> "stripe_middle";
            case "drs" -> "stripe_downright";
            case "dls" -> "stripe_downleft";
            case "ss" -> "small_stripes";
            case "cr" -> "cross";
            case "sc" -> "straight_cross";
            case "ld" -> "diagonal_left";
            case "rud" -> "diagonal_up_right";
            case "lud" -> "diagonal_up_left";
            case "rd" -> "diagonal_right";
            case "vh" -> "half_vertical";
            case "vhr" -> "half_vertical_right";
            case "hh" -> "half_horizontal";
            case "hhb" -> "half_horizontal_bottom";
            case "bl" -> "square_bottom_left";
            case "br" -> "square_bottom_right";
            case "tl" -> "square_top_left";
            case "tr" -> "square_top_right";
            case "bt" -> "triangle_bottom";
            case "tt" -> "triangle_top";
            case "bts" -> "triangles_bottom";
            case "tts" -> "triangles_top";
            case "mc" -> "circle";
            case "mr" -> "rhombus";
            case "bo" -> "border";
            case "cbo" -> "curly_border";
            case "bri" -> "bricks";
            case "gra" -> "gradient";
            case "gru" -> "gradient_up";
            case "cre" -> "creeper";
            case "sku" -> "skull";
            case "flo" -> "flower";
            case "moj" -> "mojang";
            case "glb" -> "globe";
            default -> path;
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nbtCompound(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static List<?> nbtList(Object value) {
        return value instanceof List<?> list ? list : null;
    }

    private static Object first(Map<String, Object> values, String... keys) {
        if (values == null) return null;
        for (String key : keys) if (values.containsKey(key)) return values.get(key);
        return null;
    }

    private static String string(Object value) {
        return value instanceof String text ? text : null;
    }

    private BakedModel fluidModel(Litematic.BlockState state, boolean water) {
        int level;
        try { level = Integer.parseInt(state.properties().getOrDefault("level", "0")); }
        catch (NumberFormatException ignored) { level = 0; }
        level = Math.max(0, Math.min(15, level));
        double height = level == 0 || level >= 8 ? 8.0 / 9.0 : (8.0 - level) / 9.0;
        String prefix = water ? "minecraft:block/water_" : "minecraft:block/lava_";
        BufferedImage still = water ? translucentTexture(prefix + "still", 166) : resources.texture(prefix + "still");
        BufferedImage flow = water ? translucentTexture(prefix + "flow", 166) : resources.texture(prefix + "flow");
        double[] from = {0, 0, 0}, to = {16, height * 16, 16};
        List<Quad> quads = new ArrayList<>(6);
        for (Direction direction : Direction.values()) {
            BufferedImage texture = direction == Direction.UP || direction == Direction.DOWN ? still : flow;
            double[] uv = defaultUv(direction, from, to);
            quads.add(new Quad(faceVertices(direction, from, to, uv, 0), texture, direction, water ? 0 : -1));
        }
        return new BakedModel(List.copyOf(quads));
    }

    private BufferedImage translucentTexture(String textureId, int alpha) {
        return processedTextures.computeIfAbsent(textureId + "@" + alpha, ignored -> {
            BufferedImage source = resources.texture(textureId);
            BufferedImage target = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++) {
                int color = source.getRGB(x, y);
                int adjustedAlpha = (color >>> 24) * alpha / 255;
                target.setRGB(x, y, adjustedAlpha << 24 | color & 0xffffff);
            }
            return target;
        });
    }

    private BakedModel chestModel(Litematic.BlockState state, String blockPath) {
        String type = state.properties().getOrDefault("type", "single");
        String textureName = chestTextureName(blockPath);
        if (!type.equals("single") && !textureName.equals("ender")) textureName += "_" + type;
        BufferedImage atlas = resources.texture("minecraft:entity/chest/" + textureName);
        boolean large = !type.equals("single") && !blockPath.equals("ender_chest");
        double modelX = large ? (type.equals("left") ? 0 : 1) : 1;
        double modelWidth = large ? 15 : 14;
        double lockX = large ? (type.equals("left") ? 0 : 15) : 7;
        double lockWidth = large ? 1 : 2;
        double yRotation = chestEntityRotation(state.properties().getOrDefault("facing", "north"));
        List<Quad> quads = new ArrayList<>();

        addEntityCuboid(quads, atlas, 0, 19, modelX, 0, 1, modelWidth, 10, 14,
            0, 0, 0, yRotation);
        addEntityCuboid(quads, atlas, 0, 0, modelX, 0, 0, modelWidth, 5, 14,
            0, 9, 1, yRotation);
        addEntityCuboid(quads, atlas, 0, 0, lockX, -2, 14, lockWidth, 4, 1,
            0, 9, 1, yRotation);
        return new BakedModel(List.copyOf(quads));
    }

    private boolean hasModernSignModel(String blockPath) {
        String texturePath = blockPath.replace("_wall_hanging_sign", "_hanging_sign").replace("_wall_sign", "_sign");
        return resources.hasTexture("minecraft:block/" + texturePath);
    }

    private static String chestTextureName(String blockPath) {
        if (blockPath.equals("ender_chest")) return "ender";
        if (blockPath.equals("trapped_chest")) return "trapped";
        if (!blockPath.endsWith("copper_chest")) return "normal";
        String normalized = blockPath.startsWith("waxed_") ? blockPath.substring("waxed_".length()) : blockPath;
        if (normalized.startsWith("exposed_")) return "copper_exposed";
        if (normalized.startsWith("weathered_")) return "copper_weathered";
        if (normalized.startsWith("oxidized_")) return "copper_oxidized";
        return "copper";
    }

    private BakedModel shulkerModel(Litematic.BlockState state, String blockPath) {
        String color = blockPath.equals("shulker_box") ? "" : blockPath.substring(0, blockPath.length() - "_shulker_box".length());
        String textureName = color.isEmpty() ? "shulker" : "shulker_" + color;
        BufferedImage atlas = resources.texture("minecraft:entity/shulker/" + textureName);
        int scale = Math.max(1, atlas.getWidth() / 64);
        BufferedImage top = crop(atlas, scale, 16, 0, 16, 16, false, false);
        BufferedImage bottom = crop(atlas, scale, 32, 28, 16, 16, false, false);
        BufferedImage side = new BufferedImage(16 * scale, 16 * scale, BufferedImage.TYPE_INT_ARGB);
        copy(atlas, side, scale, 0, 16, 16, 12, 0, 0);
        copy(atlas, side, scale, 4, 44, 8, 4, 4, 8);
        copy(atlas, side, scale, 0, 48, 16, 4, 0, 12);
        Map<Direction, BufferedImage> textures = Map.of(
            Direction.UP, top, Direction.DOWN, bottom,
            Direction.NORTH, side, Direction.SOUTH, side, Direction.WEST, side, Direction.EAST, side);
        return new BakedModel(List.copyOf(cuboid(new double[]{0, 0, 0}, new double[]{16, 16, 16}, textures, Set.of(), 0, 0)));
    }

    private BakedModel signModel(Litematic.BlockState state, String blockPath) {
        boolean wall = blockPath.endsWith("_wall_sign");
        String suffix = wall ? "_wall_sign" : "_sign";
        String material = blockPath.substring(0, blockPath.length() - suffix.length());
        BufferedImage atlas = resources.texture("minecraft:entity/signs/" + material);
        int scale = Math.max(1, atlas.getWidth() / 64);
        BufferedImage front = crop(atlas, scale, 2, 2, 24, 12, false, false);
        BufferedImage back = crop(atlas, scale, 28, 2, 24, 12, false, false);
        BufferedImage edge = crop(atlas, scale, 2, 0, 24, 2, false, false);
        Map<Direction, BufferedImage> boardTextures = Map.of(
            Direction.NORTH, front, Direction.SOUTH, back, Direction.UP, edge,
            Direction.DOWN, edge, Direction.WEST, edge, Direction.EAST, edge);

        double rotation;
        if (wall) rotation = chestRotation(state.properties().getOrDefault("facing", "north"));
        else {
            int index;
            try { index = Integer.parseInt(state.properties().getOrDefault("rotation", "0")); }
            catch (NumberFormatException ignored) { index = 0; }
            rotation = -index * 22.5;
        }
        double[] boardFrom = wall ? new double[]{0, 4, 14} : new double[]{0, 9.33333, 7.33333};
        double[] boardTo = wall ? new double[]{16, 12, 16} : new double[]{16, 17.33333, 8.66667};
        List<Quad> quads = new ArrayList<>(cuboid(boardFrom, boardTo, boardTextures, Set.of(), 0, rotation));
        if (!wall) {
            Map<Direction, BufferedImage> postTextures = new HashMap<>();
            for (Direction direction : Direction.values()) postTextures.put(direction, edge);
            quads.addAll(cuboid(new double[]{7.33333, 0, 7.33333}, new double[]{8.66667, 9.33333, 8.66667}, postTextures, Set.of(), 0, rotation));
        }
        return new BakedModel(List.copyOf(quads));
    }

    private static List<Quad> cuboid(double[] from, double[] to, Map<Direction, BufferedImage> textures,
                                     Set<Direction> omitted, double xRotation, double yRotation) {
        List<Quad> quads = new ArrayList<>(6);
        double[] uv = {0, 0, 16, 16};
        for (Direction direction : Direction.values()) {
            if (omitted.contains(direction)) continue;
            Vertex[] vertices = faceVertices(direction, from, to, uv, 0);
            for (int index = 0; index < vertices.length; index++) {
                vertices[index] = new Vertex(rotateBlock(vertices[index].position(), xRotation, yRotation), vertices[index].u(), vertices[index].v());
            }
            quads.add(new Quad(vertices, textures.get(direction), null, -1));
        }
        return quads;
    }

    private static int chestRotation(String facing) {
        return switch (facing) {
            case "east" -> 270;
            case "south" -> 180;
            case "west" -> 90;
            default -> 0;
        };
    }

    private static int chestEntityRotation(String facing) {
        return switch (facing) {
            case "east" -> 90;
            case "north" -> 180;
            case "west" -> 270;
            default -> 0;
        };
    }

    private static void addEntityCuboid(List<Quad> target, BufferedImage atlas, int textureU, int textureV,
                                        double x, double y, double z, double sizeX, double sizeY, double sizeZ,
                                        double pivotX, double pivotY, double pivotZ, double yRotation) {
        UnaryOperator<Vec3> transform = point -> rotateBlock(new Vec3(
            (point.x() + pivotX) / 16.0,
            (point.y() + pivotY) / 16.0,
            (point.z() + pivotZ) / 16.0
        ), 0, yRotation);

        double x2 = x + sizeX, y2 = y + sizeY, z2 = z + sizeZ;
        Vec3 p0 = new Vec3(x, y, z), p1 = new Vec3(x2, y, z);
        Vec3 p2 = new Vec3(x2, y2, z), p3 = new Vec3(x, y2, z);
        Vec3 p4 = new Vec3(x, y, z2), p5 = new Vec3(x2, y, z2);
        Vec3 p6 = new Vec3(x2, y2, z2), p7 = new Vec3(x, y2, z2);
        double u0 = textureU, u1 = u0 + sizeZ, u2 = u1 + sizeX;
        double u3 = u2 + sizeX, u4 = u2 + sizeZ, u5 = u4 + sizeX;
        double v0 = textureV, v1 = v0 + sizeZ, v2 = v1 + sizeY;

        addEntityFace(target, atlas, new Vec3[]{p5, p4, p0, p1}, u1, v0, u2, v1, transform);
        addEntityFace(target, atlas, new Vec3[]{p2, p3, p7, p6}, u2, v1, u3, v0, transform);
        addEntityFace(target, atlas, new Vec3[]{p0, p4, p7, p3}, u0, v1, u1, v2, transform);
        addEntityFace(target, atlas, new Vec3[]{p1, p0, p3, p2}, u1, v1, u2, v2, transform);
        addEntityFace(target, atlas, new Vec3[]{p5, p1, p2, p6}, u2, v1, u4, v2, transform);
        addEntityFace(target, atlas, new Vec3[]{p4, p5, p6, p7}, u4, v1, u5, v2, transform);
    }

    private static void addEntityFace(List<Quad> target, BufferedImage atlas, Vec3[] points,
                                      double u1, double v1, double u2, double v2, UnaryOperator<Vec3> transform) {
        double minU = Math.min(u1, u2), maxU = Math.max(u1, u2);
        double minV = Math.min(v1, v2), maxV = Math.max(v1, v2);
        if (maxU - minU < 1e-9 || maxV - minV < 1e-9) return;
        int scale = Math.max(1, atlas.getWidth() / 64);
        BufferedImage texture = crop(atlas, scale, (int) minU, (int) minV,
            (int) (maxU - minU), (int) (maxV - minV), false, false);
        double firstU = (u2 - minU) * 16 / (maxU - minU);
        double secondU = (u1 - minU) * 16 / (maxU - minU);
        double firstV = (v1 - minV) * 16 / (maxV - minV);
        double secondV = (v2 - minV) * 16 / (maxV - minV);
        double[] us = {firstU, secondU, secondU, firstU};
        double[] vs = {firstV, firstV, secondV, secondV};
        Vertex[] vertices = new Vertex[4];
        for (int index = 0; index < vertices.length; index++) {
            vertices[index] = new Vertex(transform.apply(points[index]), us[index], vs[index]);
        }
        target.add(new Quad(vertices, texture, null, -1));
    }

    private static BufferedImage crop(BufferedImage source, int scale, int x, int y, int width, int height, boolean flipX, boolean flipY) {
        BufferedImage target = new BufferedImage(width * scale, height * scale, BufferedImage.TYPE_INT_ARGB);
        for (int py = 0; py < target.getHeight(); py++) for (int px = 0; px < target.getWidth(); px++) {
            int sx = flipX ? target.getWidth() - 1 - px : px;
            int sy = flipY ? target.getHeight() - 1 - py : py;
            int sourceX = x * scale + sx, sourceY = y * scale + sy;
            if (sourceX < source.getWidth() && sourceY < source.getHeight()) target.setRGB(px, py, source.getRGB(sourceX, sourceY));
        }
        return target;
    }

    private static void copy(BufferedImage source, BufferedImage target, int scale,
                             int sourceX, int sourceY, int width, int height, int targetX, int targetY) {
        for (int y = 0; y < height * scale; y++) for (int x = 0; x < width * scale; x++) {
            target.setRGB(targetX * scale + x, targetY * scale + y, source.getRGB(sourceX * scale + x, sourceY * scale + y));
        }
    }

    private List<ModelRef> blockStateModels(Litematic.BlockState state) {
        Identifier block = Identifier.parse(state.name());
        if (block.namespace.equals("minecraft") && block.path.equals("grass")) {
            return List.of(new ModelRef("minecraft:block/short_grass", 0, 0, false));
        }
        if (block.namespace.equals("minecraft") && block.path.equals("deepslate") && !state.properties().containsKey("axis")) {
            return List.of(new ModelRef("minecraft:block/deepslate", 0, 0, false));
        }
        if (block.namespace.equals("minecraft") && block.path.equals("chain")) {
            String axis = state.properties().getOrDefault("axis", "y");
            return switch (axis) {
                case "x" -> List.of(new ModelRef("minecraft:block/iron_chain", 90, 90, false));
                case "z" -> List.of(new ModelRef("minecraft:block/iron_chain", 90, 0, false));
                default -> List.of(new ModelRef("minecraft:block/iron_chain", 0, 0, false));
            };
        }
        String path = "assets/" + block.namespace + "/blockstates/" + block.path + ".json";
        JsonObject root = resources.json(path).filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject).orElse(null);
        if (root == null) {
            diagnostic(state, "missing blockstate JSON");
            return List.of();
        }
        List<ModelRef> result = new ArrayList<>();

        JsonObject variants = object(root.get("variants"));
        if (variants != null) {
            for (Map.Entry<String, JsonElement> entry : variants.entrySet()) {
                if (variantMatches(entry.getKey(), state.properties())) {
                    addApply(result, entry.getValue());
                    break;
                }
            }
        }

        JsonArray multipart = array(root.get("multipart"));
        if (multipart != null) {
            for (JsonElement partValue : multipart) {
                JsonObject part = object(partValue);
                if (part == null) continue;
                if (!part.has("when") || conditionMatches(part.get("when"), state.properties())) addApply(result, part.get("apply"));
            }
        }
        return result;
    }

    private void addApply(List<ModelRef> target, JsonElement value) {
        if (value == null || value.isJsonNull()) return;
        JsonObject selected;
        if (value.isJsonArray()) {
            JsonArray options = value.getAsJsonArray();
            if (options.isEmpty()) return;
            selected = object(options.get(0));
        } else selected = object(value);
        if (selected == null || !selected.has("model")) return;
        target.add(new ModelRef(
            selected.get("model").getAsString(),
            integer(selected, "x", 0),
            integer(selected, "y", 0),
            bool(selected, "uvlock", false)
        ));
    }

    private List<Quad> bakeModel(ModelRef reference, Litematic.BlockState state) {
        ModelData model = model(reference.id(), new HashSet<>());
        if (model == null || model.elements() == null) return List.of();
        List<Quad> result = new ArrayList<>();
        for (JsonElement elementValue : model.elements()) {
            JsonObject element = object(elementValue);
            if (element == null) continue;
            double[] from = vector(element.get("from"), new double[]{0, 0, 0});
            double[] to = vector(element.get("to"), new double[]{16, 16, 16});
            JsonObject faces = object(element.get("faces"));
            if (faces == null) continue;
            JsonObject rotation = object(element.get("rotation"));
            boolean shade = !element.has("shade") || element.get("shade").getAsBoolean();
            for (Map.Entry<String, JsonElement> faceEntry : faces.entrySet()) {
                Direction direction = Direction.parse(faceEntry.getKey());
                JsonObject face = object(faceEntry.getValue());
                if (direction == null || face == null || !face.has("texture")) continue;
                String textureId = resolveTexture(model.textures(), face.get("texture").getAsString());
                if (textureId == null) diagnostic(state, "unresolved texture reference");
                else if (!resources.hasTexture(textureId)) diagnostic(state, "missing texture: " + textureId);
                BufferedImage texture = textureId == null ? resources.missingTexture() : resources.texture(textureId);
                double[] uv = face.has("uv") ? vector(face.get("uv"), defaultUv(direction, from, to)) : defaultUv(direction, from, to);
                int uvRotation = integer(face, "rotation", 0);
                Direction cullFace = Direction.parse(string(face, "cullface", null));
                int tintIndex = integer(face, "tintindex", -1);
                Vertex[] vertices = faceVertices(direction, from, to, uv, uvRotation);
                for (int index = 0; index < vertices.length; index++) {
                    Vec3 point = vertices[index].position();
                    if (rotation != null) point = rotateElement(point, rotation);
                    point = rotateBlock(point, -reference.xRotation(), -reference.yRotation());
                    vertices[index] = new Vertex(point, vertices[index].u(), vertices[index].v());
                }
                if (cullFace != null) cullFace = rotateDirection(cullFace, -reference.xRotation(), -reference.yRotation());
                result.add(new Quad(vertices, texture, cullFace, tintIndex, shade));
            }
        }
        return result;
    }

    private ModelData model(String rawId, Set<String> stack) {
        Identifier id = Identifier.parse(rawId);
        String key = id.namespace + ":" + id.path;
        ModelData cached = models.get(key);
        if (cached != null) return cached;
        if (!stack.add(key)) return null;

        String path = "assets/" + id.namespace + "/models/" + id.path + ".json";
        JsonObject root = resources.json(path).filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject).orElse(null);
        if (root == null) {
            diagnostic(rawId, "missing model JSON");
            return null;
        }
        ModelData parent = root.has("parent") ? model(root.get("parent").getAsString(), stack) : null;
        Map<String, String> textures = new LinkedHashMap<>();
        if (parent != null) textures.putAll(parent.textures());
        JsonObject ownTextures = object(root.get("textures"));
        if (ownTextures != null) ownTextures.entrySet().forEach(entry -> {
            String value = textureValue(entry.getValue());
            if (value != null) textures.put(entry.getKey(), value);
        });
        JsonArray elements = root.has("elements") ? array(root.get("elements")) : parent == null ? null : parent.elements();
        ModelData resolved = new ModelData(Map.copyOf(textures), elements == null ? null : elements.deepCopy());
        models.put(key, resolved);
        stack.remove(key);
        return resolved;
    }

    private List<Quad> fallbackCube(Litematic.BlockState state) {
        String textureId = fallbackTexture(state.name());
        BufferedImage texture = resources.texture(textureId);
        List<Quad> result = new ArrayList<>(6);
        double[] from = {0, 0, 0}, to = {16, 16, 16}, uv = {0, 0, 16, 16};
        for (Direction direction : Direction.values()) {
            result.add(new Quad(faceVertices(direction, from, to, uv, 0), texture, direction, -1));
        }
        return result;
    }

    private static String fallbackTexture(String blockName) {
        Identifier id = Identifier.parse(blockName);
        return id.namespace + ":block/" + id.path;
    }

    private static boolean isSelfCullingTranslucent(String name) {
        String path = Identifier.parse(name).path;
        return !path.contains("pane") && (path.equals("glass") || path.endsWith("_stained_glass") || path.equals("tinted_glass"));
    }

    private static boolean isWater(String name) {
        String path = Identifier.parse(name).path;
        return path.equals("water") || path.equals("bubble_column");
    }

    private static boolean isLava(String name) {
        return Identifier.parse(name).path.equals("lava");
    }

    private static boolean variantMatches(String variant, Map<String, String> properties) {
        if (variant.isBlank()) return true;
        for (String condition : variant.split(",")) {
            String[] pair = condition.split("=", 2);
            if (pair.length != 2) return false;
            String actual = properties.get(pair[0]);
            if (actual == null || java.util.Arrays.stream(pair[1].split("\\|", -1)).noneMatch(actual::equals)) return false;
        }
        return true;
    }

    private static boolean conditionMatches(JsonElement value, Map<String, String> properties) {
        JsonObject condition = object(value);
        if (condition == null) return true;
        if (condition.has("OR")) {
            JsonArray options = array(condition.get("OR"));
            return options != null && java.util.stream.StreamSupport.stream(options.spliterator(), false).anyMatch(option -> conditionMatches(option, properties));
        }
        if (condition.has("AND")) {
            JsonArray options = array(condition.get("AND"));
            return options != null && java.util.stream.StreamSupport.stream(options.spliterator(), false).allMatch(option -> conditionMatches(option, properties));
        }
        for (Map.Entry<String, JsonElement> entry : condition.entrySet()) {
            String actual = properties.get(entry.getKey());
            if (actual == null) return false;
            String expected = entry.getValue().getAsString();
            boolean negate = expected.startsWith("!");
            if (negate) expected = expected.substring(1);
            boolean match = java.util.Arrays.asList(expected.split("\\|")).contains(actual);
            if (negate == match) return false;
        }
        return true;
    }

    private static String resolveTexture(Map<String, String> textures, String reference) {
        String value = reference;
        Set<String> visited = new HashSet<>();
        while (value.startsWith("#")) {
            String key = value.substring(1);
            if (!visited.add(key)) return null;
            value = textures.get(key);
            if (value == null) return null;
        }
        return value.contains(":") ? value : "minecraft:" + value;
    }

    private static String textureValue(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        if (value.isJsonPrimitive()) return value.getAsString();
        JsonObject object = object(value);
        return object != null && object.has("sprite") ? object.get("sprite").getAsString() : null;
    }

    private static Vertex[] faceVertices(Direction direction, double[] from, double[] to, double[] uv, int rotation) {
        double x1 = from[0] / 16, y1 = from[1] / 16, z1 = from[2] / 16;
        double x2 = to[0] / 16, y2 = to[1] / 16, z2 = to[2] / 16;
        Vec3[] points = switch (direction) {
            case SOUTH -> new Vec3[]{new Vec3(x1, y2, z2), new Vec3(x2, y2, z2), new Vec3(x2, y1, z2), new Vec3(x1, y1, z2)};
            case NORTH -> new Vec3[]{new Vec3(x2, y2, z1), new Vec3(x1, y2, z1), new Vec3(x1, y1, z1), new Vec3(x2, y1, z1)};
            case EAST -> new Vec3[]{new Vec3(x2, y2, z2), new Vec3(x2, y2, z1), new Vec3(x2, y1, z1), new Vec3(x2, y1, z2)};
            case WEST -> new Vec3[]{new Vec3(x1, y2, z1), new Vec3(x1, y2, z2), new Vec3(x1, y1, z2), new Vec3(x1, y1, z1)};
            case UP -> new Vec3[]{new Vec3(x1, y2, z1), new Vec3(x2, y2, z1), new Vec3(x2, y2, z2), new Vec3(x1, y2, z2)};
            case DOWN -> new Vec3[]{new Vec3(x1, y1, z2), new Vec3(x2, y1, z2), new Vec3(x2, y1, z1), new Vec3(x1, y1, z1)};
        };
        double[][] coordinates = {{uv[0], uv[1]}, {uv[2], uv[1]}, {uv[2], uv[3]}, {uv[0], uv[3]}};
        int shift = Math.floorMod(rotation / 90, 4);
        Vertex[] result = new Vertex[4];
        for (int index = 0; index < 4; index++) {
            double[] texture = coordinates[Math.floorMod(index - shift, 4)];
            result[index] = new Vertex(points[index], texture[0], texture[1]);
        }
        return result;
    }

    private static double[] defaultUv(Direction direction, double[] from, double[] to) {
        return switch (direction) {
            case DOWN -> new double[]{from[0], 16 - to[2], to[0], 16 - from[2]};
            case UP -> new double[]{from[0], from[2], to[0], to[2]};
            case NORTH -> new double[]{16 - to[0], 16 - to[1], 16 - from[0], 16 - from[1]};
            case SOUTH -> new double[]{from[0], 16 - to[1], to[0], 16 - from[1]};
            case WEST -> new double[]{from[2], 16 - to[1], to[2], 16 - from[1]};
            case EAST -> new double[]{16 - to[2], 16 - to[1], 16 - from[2], 16 - from[1]};
        };
    }

    private static Vec3 rotateElement(Vec3 point, JsonObject rotation) {
        double[] origin = vector(rotation.get("origin"), new double[]{8, 8, 8});
        Vec3 center = new Vec3(origin[0] / 16, origin[1] / 16, origin[2] / 16);
        double angle = Math.toRadians(rotation.has("angle") ? rotation.get("angle").getAsDouble() : 0);
        String axis = string(rotation, "axis", "y");
        Vec3 result = rotate(point, center, axis, angle);
        if (bool(rotation, "rescale", false) && Math.abs(Math.cos(angle)) > 1e-6) {
            double scale = 1 / Math.cos(angle);
            Vec3 delta = result.subtract(center);
            result = switch (axis) {
                case "x" -> new Vec3(center.x() + delta.x(), center.y() + delta.y() * scale, center.z() + delta.z() * scale);
                case "z" -> new Vec3(center.x() + delta.x() * scale, center.y() + delta.y() * scale, center.z() + delta.z());
                default -> new Vec3(center.x() + delta.x() * scale, center.y() + delta.y(), center.z() + delta.z() * scale);
            };
        }
        return result;
    }

    private static Vec3 rotateBlock(Vec3 point, double xDegrees, double yDegrees) {
        Vec3 center = new Vec3(0.5, 0.5, 0.5);
        Vec3 result = rotate(point, center, "x", Math.toRadians(xDegrees));
        return rotate(result, center, "y", Math.toRadians(yDegrees));
    }

    private static Vec3 rotate(Vec3 point, Vec3 center, String axis, double angle) {
        double x = point.x() - center.x(), y = point.y() - center.y(), z = point.z() - center.z();
        double sin = Math.sin(angle), cos = Math.cos(angle);
        return switch (axis) {
            case "x" -> new Vec3(center.x() + x, center.y() + y * cos - z * sin, center.z() + y * sin + z * cos);
            case "z" -> new Vec3(center.x() + x * cos - y * sin, center.y() + x * sin + y * cos, center.z() + z);
            default -> new Vec3(center.x() + x * cos + z * sin, center.y() + y, center.z() - x * sin + z * cos);
        };
    }

    private static Direction rotateDirection(Direction direction, int xDegrees, int yDegrees) {
        Vec3 value = rotateBlock(new Vec3(0.5 + direction.x, 0.5 + direction.y, 0.5 + direction.z), xDegrees, yDegrees).subtract(new Vec3(0.5, 0.5, 0.5));
        if (Math.abs(value.x()) > 0.5) return value.x() > 0 ? Direction.EAST : Direction.WEST;
        if (Math.abs(value.y()) > 0.5) return value.y() > 0 ? Direction.UP : Direction.DOWN;
        return value.z() > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static JsonObject object(JsonElement value) { return value != null && value.isJsonObject() ? value.getAsJsonObject() : null; }
    private static JsonArray array(JsonElement value) { return value != null && value.isJsonArray() ? value.getAsJsonArray() : null; }
    private static int integer(JsonObject object, String key, int fallback) { return object.has(key) ? object.get(key).getAsInt() : fallback; }
    private static boolean bool(JsonObject object, String key, boolean fallback) { return object.has(key) ? object.get(key).getAsBoolean() : fallback; }
    private static String string(JsonObject object, String key, String fallback) { return object.has(key) ? object.get(key).getAsString() : fallback; }
    private static double[] vector(JsonElement value, double[] fallback) {
        JsonArray array = array(value);
        if (array == null || array.size() < fallback.length) return fallback.clone();
        double[] result = new double[fallback.length];
        for (int index = 0; index < result.length; index++) result[index] = array.get(index).getAsDouble();
        return result;
    }

    private record Identifier(String namespace, String path) {
        static Identifier parse(String value) {
            String normalized = value;
            if (normalized.startsWith("/")) normalized = normalized.substring(1);
            int separator = normalized.indexOf(':');
            return separator < 0 ? new Identifier("minecraft", normalized) : new Identifier(normalized.substring(0, separator), normalized.substring(separator + 1));
        }
    }
}
