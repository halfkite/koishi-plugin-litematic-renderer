package dev.qqbot.renderbridge;

import com.glisco.isometricrenders.render.AreaRenderable;
import com.glisco.isometricrenders.render.RenderableDispatcher;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import fi.dy.masa.litematica.render.schematic.WorldRendererSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import io.wispforest.worldmesher.WorldMesh;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class RenderBridgeClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("litematic-render-bridge");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String BASE_PACK_PREFIX = "XeKr";
    private static final String BASE_PACK_SUFFIX = "3.6forMC1.20.2~1.21.5.zip";
    private static final String ADDON_PACK_PREFIX = "XKRDA";
    private static final String ADDON_PACK_SUFFIX = "1.19.4~1.21snapshot.zip";

    private final Path root = FabricLoader.getInstance().getGameDir().resolve("render-bridge");
    private final Path jobs = root.resolve("jobs");
    private final Path processing = root.resolve("processing");
    private final Path results = root.resolve("results");
    private ActiveJob activeJob;
    private Path claimedJobFile;
    private String claimedJobId;
    private int statusCooldown;

    @Override
    public void onInitializeClient() {
        try {
            Files.createDirectories(jobs);
            Files.createDirectories(processing);
            Files.createDirectories(results);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create render bridge directories", exception);
        }

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        LOGGER.info("Litematic render bridge queue: {}", root);
    }

    private void tick(MinecraftClient client) {
        if (--statusCooldown <= 0) {
            statusCooldown = 20;
            writeStatus(client);
        }

        try {
            if (activeJob != null) {
                if (activeJob.mesh.canRender()) {
                    finishActiveJob(client);
                } else if (System.currentTimeMillis() - activeJob.startedAt > 120_000) {
                    throw new IllegalStateException("WorldMesher timed out while building the schematic");
                }
                return;
            }

            Path jobFile = nextJob();
            if (jobFile != null) {
                claimedJobFile = jobFile;
                startJob(client, jobFile);
            }
        } catch (Throwable throwable) {
            failActiveJob(throwable);
        }
    }

    private Path nextJob() throws IOException {
        List<Path> queued = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(jobs, "*.job.json")) {
            for (Path path : stream) queued.add(path);
        }
        queued.sort(Comparator.comparing(Path::getFileName));
        if (queued.isEmpty()) return null;

        Path source = queued.getFirst();
        Path target = processing.resolve(source.getFileName());
        return Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void startJob(MinecraftClient client, Path jobFile) throws Exception {
        RenderJob request;
        try (Reader reader = Files.newBufferedReader(jobFile)) {
            request = GSON.fromJson(reader, RenderJob.class);
        }
        if (request == null || request.id == null || request.input == null || request.outputDirectory == null) {
            throw new IllegalArgumentException("Invalid render job");
        }
        claimedJobId = request.id;
        if (client.world == null || client.player == null) {
            writeResult(request.id, false, "Minecraft must be inside a world before rendering");
            Files.deleteIfExists(jobFile);
            claimedJobFile = null;
            claimedJobId = null;
            return;
        }
        verifyResourcePacks(client);

        Path input = Path.of(request.input).toAbsolutePath().normalize();
        Path output = Path.of(request.outputDirectory).toAbsolutePath().normalize();
        Files.createDirectories(output);

        LitematicaSchematic schematic = LitematicaSchematic.createFromFile(input.getParent().toFile(), input.getFileName().toString());
        if (schematic == null) throw new IOException("Litematica could not read " + input);

        SchematicPlacement placement = SchematicPlacement.createTemporary(schematic, BlockPos.ORIGIN);
        if (!placement.shouldRenderEnclosingBox()) placement.toggleRenderEnclosingBox();
        Box initialBox = placement.getEclosingBox();
        if (initialBox == null) throw new IOException("Litematica schematic has no enabled regions: " + input);
        int minY = Math.min(initialBox.getPos1().getY(), initialBox.getPos2().getY());
        placement.setOrigin(new BlockPos(0, client.world.getBottomY() + 16 - minY, 0), message -> {});

        WorldRendererSchematic renderer = new WorldRendererSchematic(client);
        WorldSchematic schematicWorld = SchematicWorldHandler.createSchematicWorld(renderer);
        if (schematicWorld == null) throw new IllegalStateException("Unable to create isolated schematic world");
        if (!schematic.placeToWorld(schematicWorld, placement, false, false)) {
            throw new IOException("Litematica failed to place the schematic into the render world");
        }

        Box box = placement.getEclosingBox();
        BlockPos min = min(box.getPos1(), box.getPos2());
        BlockPos max = max(box.getPos1(), box.getPos2());
        WorldMesh mesh = new WorldMesh.Builder(schematicWorld, min, max).freezeEntities().build();
        mesh.scheduleRebuild();
        activeJob = new ActiveJob(request, jobFile, output, mesh, System.currentTimeMillis());
        LOGGER.info("Building render mesh for {} from {} to {}", request.id, min, max);
    }

    private void finishActiveJob(MinecraftClient client) throws Exception {
        ActiveJob active = activeJob;
        if (!active.mesh.canRender()) throw new IllegalStateException("WorldMesher did not produce a renderable mesh");

        int outputSize = clamp(active.request.resolution, 256, 4096, 1024);
        int supersampling = clamp(active.request.supersampling, 1, 4, 2);
        int captureSize = Math.min(8192, outputSize * supersampling);
        int rotation = normalizeAngle(active.request.rotation, 135);
        int slant = Math.max(-90, Math.min(90, active.request.slant == null ? 36 : active.request.slant));
        double fill = active.request.fill == null ? 0.78 : Math.max(0.1, Math.min(0.98, active.request.fill));
        String background = active.request.background == null ? "#000000" : active.request.background;
        boolean transparentBackground = Boolean.TRUE.equals(active.request.transparentBackground);

        AreaRenderable renderable = new AreaRenderable(active.mesh);
        var properties = renderable.properties();
        properties.rotation.set(rotation);
        properties.slant.set(slant);
        properties.scale.set(calculateScale(active.mesh, rotation, slant, fill));
        renderOne(renderable, captureSize, outputSize, background, transparentBackground, active.output.resolve("isometric.png"));

        properties.rotation.set((rotation + 180) % 360);
        properties.scale.set(calculateScale(active.mesh, (rotation + 180) % 360, slant, fill));
        renderOne(renderable, captureSize, outputSize, background, transparentBackground, active.output.resolve("isometric-reverse.png"));

        active.mesh.reset();
        Files.deleteIfExists(active.jobFile);
        writeResult(active.request.id, true, null);
        LOGGER.info("Completed render job {} using active packs {}", active.request.id, client.getResourcePackManager().getEnabledIds());
        activeJob = null;
        claimedJobFile = null;
        claimedJobId = null;
    }

    private void renderOne(AreaRenderable renderable, int captureSize, int outputSize, String background, boolean transparentBackground, Path output) throws IOException {
        Path raw = output.resolveSibling(output.getFileName() + ".raw.png");
        try (NativeImage image = RenderableDispatcher.drawIntoImage(renderable, 0, captureSize)) {
            image.writeTo(raw);
        }

        BufferedImage source = ImageIO.read(raw.toFile());
        BufferedImage target = new BufferedImage(outputSize, outputSize, transparentBackground ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            if (!transparentBackground) {
                graphics.setColor(Color.decode(background));
                graphics.fillRect(0, 0, outputSize, outputSize);
            }
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, outputSize, outputSize, null);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(target, "PNG", output.toFile());
        Files.deleteIfExists(raw);
    }

    private int calculateScale(WorldMesh mesh, int rotation, int slant, double fill) {
        var dimensions = mesh.dimensions();
        double halfX = dimensions.getLengthX() / 2.0;
        double halfY = dimensions.getLengthY() / 2.0;
        double halfZ = dimensions.getLengthZ() / 2.0;
        double yaw = Math.toRadians(rotation);
        double pitch = Math.toRadians(slant);
        double cosYaw = Math.cos(yaw), sinYaw = Math.sin(yaw);
        double cosPitch = Math.cos(pitch), sinPitch = Math.sin(pitch);
        double maxX = 0, maxY = 0;

        for (int xSign : new int[] {-1, 1}) {
            for (int ySign : new int[] {-1, 1}) {
                for (int zSign : new int[] {-1, 1}) {
                    double x = halfX * xSign, y = halfY * ySign, z = halfZ * zSign;
                    double rotatedX = cosYaw * x + sinYaw * z;
                    double rotatedZ = -sinYaw * x + cosYaw * z;
                    double rotatedY = cosPitch * y - sinPitch * rotatedZ;
                    maxX = Math.max(maxX, Math.abs(rotatedX));
                    maxY = Math.max(maxY, Math.abs(rotatedY));
                }
            }
        }
        double modelScale = fill / Math.max(0.001, Math.max(maxX, maxY));
        return Math.max(1, Math.min(500, (int) Math.floor(modelScale * 1000)));
    }

    private void verifyResourcePacks(MinecraftClient client) {
        List<String> enabled = List.copyOf(client.getResourcePackManager().getEnabledIds());
        boolean baseEnabled = enabled.stream().anyMatch(id -> packIdMatches(id, BASE_PACK_PREFIX, BASE_PACK_SUFFIX));
        boolean addonEnabled = enabled.stream().anyMatch(id -> packIdMatches(id, ADDON_PACK_PREFIX, ADDON_PACK_SUFFIX));
        if (!baseEnabled || !addonEnabled) {
            throw new IllegalStateException("Required redstone resource packs are not enabled: " + enabled);
        }
    }

    private boolean packIdMatches(String id, String prefix, String suffix) {
        return id.contains(prefix) && id.contains(suffix);
    }

    private void failActiveJob(Throwable throwable) {
        LOGGER.error("Render bridge job failed", throwable);
        try {
            if (activeJob != null) activeJob.mesh.reset();
            if (claimedJobFile != null) Files.deleteIfExists(claimedJobFile);
            if (claimedJobId != null) writeResult(claimedJobId, false, throwable.getMessage());
        } catch (IOException cleanupError) {
            LOGGER.error("Unable to write failed render result", cleanupError);
        } finally {
            activeJob = null;
            claimedJobFile = null;
            claimedJobId = null;
        }
    }

    private void writeStatus(MinecraftClient client) {
        JsonObject status = new JsonObject();
        status.addProperty("timestamp", Instant.now().toEpochMilli());
        status.addProperty("ready", client.world != null && client.player != null && activeJob == null);
        status.addProperty("inWorld", client.world != null);
        status.addProperty("busy", activeJob != null);
        status.add("resourcePacks", GSON.toJsonTree(client.getResourcePackManager().getEnabledIds()));
        try {
            writeJsonAtomic(root.resolve("status.json"), status);
        } catch (IOException exception) {
            LOGGER.warn("Unable to update render bridge status", exception);
        }
    }

    private void writeResult(String id, boolean success, String error) throws IOException {
        JsonObject result = new JsonObject();
        result.addProperty("id", id);
        result.addProperty("success", success);
        result.addProperty("timestamp", Instant.now().toEpochMilli());
        if (error != null) result.addProperty("error", error);
        writeJsonAtomic(results.resolve(id + ".result.json"), result);
    }

    private void writeJsonAtomic(Path target, JsonObject value) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary)) {
            GSON.toJson(value, writer);
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static BlockPos min(BlockPos first, BlockPos second) {
        return new BlockPos(Math.min(first.getX(), second.getX()), Math.min(first.getY(), second.getY()), Math.min(first.getZ(), second.getZ()));
    }

    private static BlockPos max(BlockPos first, BlockPos second) {
        return new BlockPos(Math.max(first.getX(), second.getX()), Math.max(first.getY(), second.getY()), Math.max(first.getZ(), second.getZ()));
    }

    private static int normalizeAngle(Integer value, int fallback) {
        int angle = value == null ? fallback : value;
        return Math.floorMod(angle, 360);
    }

    private static int clamp(Integer value, int min, int max, int fallback) {
        return Math.max(min, Math.min(max, value == null ? fallback : value));
    }

    private record RenderJob(String id, String input, String outputDirectory, Integer resolution, Integer supersampling,
                             Integer rotation, Integer slant, Double fill, String background, Boolean transparentBackground) {}

    private record ActiveJob(RenderJob request, Path jobFile, Path output, WorldMesh mesh, long startedAt) {}
}
