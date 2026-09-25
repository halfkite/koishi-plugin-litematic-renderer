package dev.qqbot.gpuruntime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import org.lwjgl.opengl.GL11;
import org.lwjgl.sdl.SDLVideo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class GpuRuntimeClient implements ClientModInitializer {
    private static GpuRuntimeClient instance;
    public static void clientTick(Minecraft client) {
        if (instance != null) instance.tick(client);
    }
    private static final Logger LOGGER = LoggerFactory.getLogger("litematic-gpu-runtime");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String RENDER_WORLD = "litematic-gpu-runtime-void";
    private static final long JOB_TIMEOUT_NANOS = java.time.Duration.ofMinutes(15).toNanos();

    private final Path root = FabricLoader.getInstance().getGameDir().resolve("gpu-render-runtime");
    private final Path jobs = root.resolve("jobs");
    private final Path processing = root.resolve("processing");
    private final Path results = root.resolve("results");
    private final Path renderWorld = FabricLoader.getInstance().getGameDir().resolve("saves").resolve(RENDER_WORLD);
    private ActiveJob active;
    private volatile boolean mapArtBusy;
    private boolean openingWorld;
    private boolean windowHidden;
    private boolean clientDefaultsApplied;
    private boolean initialResourcesReady;
    private ClientLevel registryWorld;
    private boolean registryWarningLogged;
    private boolean worldDefaultsScheduled;
    private volatile boolean worldDefaultsApplied;
    private ScheduledExecutorService tickExecutor;
    private int scanCooldown;
    private int statusCooldown;
    private String gpu = "unknown";
    private int maxTextureSize;

    @Override public void onInitializeClient() {
        try {
            Files.createDirectories(jobs); Files.createDirectories(processing); Files.createDirectories(results);
            installRenderWorld();
        } catch (IOException error) { throw new IllegalStateException("Unable to initialize GPU render queue", error); }
        instance = this;
        LOGGER.info("Litematic GPU runtime queue: {}, night vision={}, brightness level={}",
                root, QuickLitematicaPreview3D.nightVisionEnabled(), QuickLitematicaPreview3D.nightVisionLevel());
    }

    private void tick(Minecraft client) {
        try {
            hideWindow(client);
            applyClientDefaults(client);
            applyWorldDefaults(client);
            if (!initialResourcesReady && client.isGameLoadFinished()) {
                initialResourcesReady = true;
                LOGGER.info("Minecraft initial resources ready; GPU render queue is accepting jobs");
            }
            if (--statusCooldown <= 0) { statusCooldown = 20; writeStatus(client); }
            if (!initialResourcesReady) return;
            if (client.level != null && !synchronizeLitematicaRegistries(client.level)) return;
            if (active != null) { advanceActive(); return; }
            if (mapArtBusy) return;
            if (--scanCooldown > 0) return;
            scanCooldown = 10;
            Path queued = nextJob();
            if (queued == null) return;
            if (client.level == null) { openRenderWorld(client); return; }
            startJob(queued);
        } catch (Throwable error) { failActive(error); }
    }

    private boolean synchronizeLitematicaRegistries(ClientLevel level) {
        if (registryWorld == level) return true;

        RegistryAccess registries = level.registryAccess();
        if (!(registries instanceof RegistryAccess.Frozen frozen)) {
            if (!registryWarningLogged) {
                LOGGER.error("Minecraft 26.3 client level did not expose a frozen registry access: {}",
                        registries.getClass().getName());
                registryWarningLogged = true;
            }
            return false;
        }
        if (registries.lookup(Registries.BLOCK).isEmpty()) {
            if (!registryWarningLogged) {
                LOGGER.error("Minecraft 26.3 client level registry access is missing minecraft:block: {}",
                        registries.getClass().getName());
                registryWarningLogged = true;
            }
            return false;
        }

        SchematicWorldHandler.INSTANCE.setDynamicRegistryManager(frozen);
        registryWorld = level;
        registryWarningLogged = false;
        LOGGER.info("Synchronized Litematica registry access from render world: {} (minecraft:block available)",
                level.dimension().identifier());
        return true;
    }

    private void applyClientDefaults(Minecraft client) {
        if (clientDefaultsApplied) return;
        client.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0D);
        clientDefaultsApplied = true;
        LOGGER.info("Runtime defaults applied: master volume 0");
    }

    private void applyWorldDefaults(Minecraft client) {
        if (client.level == null) {
            worldDefaultsScheduled = false;
            worldDefaultsApplied = false;
            return;
        }
        if (client.gameMode != null) client.gameMode.setLocalMode(GameType.SPECTATOR);
        if (worldDefaultsScheduled || client.player == null) return;
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) return;
        worldDefaultsScheduled = true;
        java.util.UUID playerId = client.player.getUUID();
        server.execute(() -> {
            server.getGameRules().set(GameRules.SPECTATORS_GENERATE_CHUNKS, false, server);
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) player.setGameMode(GameType.SPECTATOR);
            worldDefaultsApplied = true;
            LOGGER.info("Render world defaults applied: world={}, spectator=true, spectatorsGenerateChunks=false", RENDER_WORLD);
        });
    }

    private void hideWindow(Minecraft client) {
        if (windowHidden) return;
        long handle = client.getWindow().handle();
        if (handle != 0L) {
            SDLVideo.SDL_HideWindow(handle);
            gpu = String.valueOf(GL11.glGetString(GL11.GL_RENDERER));
            maxTextureSize = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
            windowHidden = true;
            LOGGER.info("Hidden GPU context ready: {}, max texture {}", gpu, maxTextureSize);
        }
    }

    private void startJob(Path queued) throws Exception {
        Path claimed = processing.resolve(queued.getFileName());
        Files.move(queued, claimed, StandardCopyOption.REPLACE_EXISTING);
        RenderJob request;
        try (Reader reader = Files.newBufferedReader(claimed)) { request = GSON.fromJson(reader, RenderJob.class); }
        if (request == null || request.id == null || request.input == null || request.outputDirectory == null
                || request.views == null || request.views.isEmpty()) throw new RenderException("INVALID_REQUEST", "Invalid GPU render job");
        Path input = Path.of(request.input).toAbsolutePath().normalize();
        Path output = Path.of(request.outputDirectory).toAbsolutePath().normalize();
        Files.createDirectories(output);
        validateViews(request.views, output);
        if (request.views.size() == 1 && "extra-map-art".equals(request.views.getFirst().id)) {
            mapArtBusy = true;
            event("accepted", request.id, 0.0, "map-art-colors");
            RenderJob mapRequest = request;
            Thread.startVirtualThread(() -> {
                long started = System.nanoTime();
                try {
                    Path image = output.resolve("extra-map-art.png");
                    QuickLitematicaPreview3D.MapArtImage dimensions = QuickLitematicaPreview3D.renderMapArt(input, image);
                    long elapsed = (System.nanoTime() - started) / 1_000_000L;
                    writeResult(new RenderResult(mapRequest.id, true, null, null, elapsed, false, gpu,
                            List.of(new ResultImage("extra-map-art", image.getFileName().toString(),
                                    dimensions.width(), dimensions.height(), image.toAbsolutePath().toString()))));
                    event("complete", mapRequest.id, 1.0, "complete");
                } catch (Throwable error) {
                    LOGGER.error("Minecraft map-color render failed", error);
                    writeResult(new RenderResult(mapRequest.id, false, "MAP_ART_FAILED", error.getMessage(),
                            (System.nanoTime() - started) / 1_000_000L, false, gpu, List.of()));
                    event("error", mapRequest.id, 0.0, "MAP_ART_FAILED");
                } finally {
                    try { Files.deleteIfExists(claimed); } catch (IOException ignored) { }
                    mapArtBusy = false;
                }
            });
            return;
        }
        active = new ActiveJob(request, claimed, output, QuickLitematicaPreview3D.createHeadlessPreview(input), System.nanoTime());
        event("accepted", request.id, 0.0, "building-mesh");
    }

    private void advanceActive() {
        ActiveJob job = active;
        if (job.error != null) { failActive(job.error); return; }
        if (System.nanoTime() - job.startedAt > JOB_TIMEOUT_NANOS) { failActive(new RenderException("TIMEOUT", "GPU render job timed out")); return; }
        if (job.preview.isFailed()) { failActive(new RenderException("SCHEMATIC_INVALID", "Preview state: " + job.preview.stateName())); return; }
        if (!job.preview.isReady()) { event("progress", job.request.id, job.preview.progress(), "building-mesh"); return; }
        if (job.exporting) return;
        if (job.viewIndex >= job.request.views.size()) { completeActive(); return; }
        View view = job.request.views.get(job.viewIndex);
        try {
            int captureWidth = Math.multiplyExact(view.width, view.supersampling);
            int captureHeight = Math.multiplyExact(view.height, view.supersampling);
            Path raw = job.output.resolve(view.id + ".raw.png");
            job.exporting = true;
            event("progress", job.request.id, (double) job.viewIndex / job.request.views.size(), "rendering-" + view.id);
            job.preview.export(raw, captureWidth, captureHeight, background(view), view.yaw, view.pitch,
                    value(view.zoom, 1.0), view.autoFill == null || view.autoFill, effectiveBrightness(view),
                    error -> finishView(job, view, raw, error));
        } catch (Throwable error) { failActive(error); }
    }

    private void finishView(ActiveJob job, View view, Path raw, Throwable error) {
        try {
            if (error != null) throw error;
            Path output = job.output.resolve(view.id + ".png");
            if (view.supersampling == 1) Files.move(raw, output, StandardCopyOption.REPLACE_EXISTING);
            else resize(raw, output, view.width, view.height);
            job.images.add(new ResultImage(view.id, view.id + ".png", view.width, view.height, output.toAbsolutePath().toString()));
            job.viewIndex++;
        } catch (Throwable failure) {
            // 单个视角导出偶发失败（大分辨率下 GPU 截帧瞬时错误）自动重试一次，仍失败才判整个任务失败
            if (job.retriedViews.add(view.id())) {
                LOGGER.warn("视角 {} 导出失败，自动重试: {}", view.id(), failure.toString());
            } else {
                job.error = failure;
            }
        }
        finally { job.exporting = false; try { Files.deleteIfExists(raw); } catch (IOException ignored) {} }
    }

    private void completeActive() {
        ActiveJob job = active;
        long elapsed = (System.nanoTime() - job.startedAt) / 1_000_000L;
        writeResult(new RenderResult(job.request.id, true, null, null, elapsed, false, gpu, job.images));
        cleanup(job); event("complete", job.request.id, 1.0, "complete"); active = null;
    }

    private void failActive(Throwable error) {
        LOGGER.error("GPU render job failed", error);
        ActiveJob job = active;
        if (job != null) {
            String code = error instanceof RenderException typed ? typed.code : "RUNTIME_FAILURE";
            writeResult(new RenderResult(job.request.id, false, code, error.getMessage(),
                    (System.nanoTime() - job.startedAt) / 1_000_000L, false, gpu, List.of()));
            cleanup(job); event("error", job.request.id, 0.0, code); active = null;
        }
    }

    private void validateViews(List<View> views, Path output) throws Exception {
        FileStore store = Files.getFileStore(output);
        long diskEstimate = 0;
        long configuredVramBudget = Long.getLong("gpu.render.vramBudgetBytes", 0L);
        for (View view : views) {
            if (view.id == null || !view.id.matches("[A-Za-z0-9._-]{1,80}")) throw new RenderException("INVALID_VIEW", "Invalid view id");
            if (view.width <= 0 || view.height <= 0 || view.supersampling <= 0) throw new RenderException("INVALID_VIEW", "Invalid view dimensions");
            int width = Math.multiplyExact(view.width, view.supersampling);
            int height = Math.multiplyExact(view.height, view.supersampling);
            if (width > maxTextureSize || height > maxTextureSize) {
                throw new RenderException("TEXTURE_SIZE_EXCEEDED", "Requested " + width + "x" + height + ", GPU limit is " + maxTextureSize);
            }
            long renderBytes = Math.multiplyExact(Math.multiplyExact((long) width, height), 12L);
            if (configuredVramBudget > 0 && renderBytes > configuredVramBudget) {
                throw new RenderException("VRAM_ESTIMATE_EXCEEDED", "Estimated render targets need " + renderBytes + " bytes");
            }
            diskEstimate = Math.addExact(diskEstimate, Math.multiplyExact((long) view.width, view.height) * 4L);
        }
        if (store.getUsableSpace() < diskEstimate) throw new RenderException("DISK_SPACE_EXCEEDED", "Insufficient disk space for PNG output");
    }

    private void openRenderWorld(Minecraft client) throws IOException {
        if (openingWorld) return;
        if (!client.getLevelSource().levelExists(RENDER_WORLD)) throw new IOException("Bundled void render world is unavailable");
        client.options.renderDistance().set(2); client.options.simulationDistance().set(5); client.options.biomeBlendRadius().set(0);
        openingWorld = true;
        new WorldOpenFlows(client, client.getLevelSource()).openWorld(RENDER_WORLD, () -> openingWorld = false);
    }

    private void installRenderWorld() throws IOException {
        copyWorld("level.dat"); copyWorld("data/minecraft/world_gen_settings.dat");
    }
    private void copyWorld(String relative) throws IOException {
        Path target = renderWorld.resolve(relative); if (Files.exists(target)) return; Files.createDirectories(target.getParent());
        try (InputStream input = getClass().getResourceAsStream("/quickcraft-render-world/" + relative)) {
            if (input == null) throw new IOException("Missing render world resource " + relative); Files.copy(input, target);
        }
    }

    private Path nextJob() throws IOException {
        List<Path> queued = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(jobs, "*.job.json")) { for (Path path : stream) queued.add(path); }
        queued.sort(Comparator.comparing(path -> path.getFileName().toString())); return queued.isEmpty() ? null : queued.getFirst();
    }

    private void writeStatus(Minecraft client) {
        try {
            List<String> packs = client.getResourcePackRepository().getSelectedIds().stream().sorted().toList();
            String fingerprint = Integer.toHexString(packs.hashCode());
            double progress = active == null ? 0.0 : active.preview.progress();
            String stage = mapArtBusy ? "map-art-colors" : active == null ? "idle" : active.exporting ? "rendering" : "building";
            writeAtomic(root.resolve("status.json"), GSON.toJson(new RenderStatus(System.currentTimeMillis(),
                    active == null && !mapArtBusy && initialResourcesReady, active != null || mapArtBusy, client.level != null,
                    "0.1.1", "26.3", gpu, maxTextureSize, fingerprint, progress, stage,
                    clientDefaultsApplied, worldDefaultsApplied, worldDefaultsApplied, RENDER_WORLD,
                    QuickLitematicaPreview3D.nightVisionEnabled(), QuickLitematicaPreview3D.nightVisionLevel())));
        } catch (IOException error) { LOGGER.warn("Unable to write GPU runtime status", error); }
    }

    private void writeResult(RenderResult result) {
        try { writeAtomic(results.resolve(result.id + ".result.json"), GSON.toJson(result)); }
        catch (IOException error) { LOGGER.error("Unable to write GPU result", error); }
    }
    private static void writeAtomic(Path target, String json) throws IOException { Files.createDirectories(target.getParent()); Path tmp=target.resolveSibling(target.getFileName()+".tmp");Files.writeString(tmp,json);Files.move(tmp,target,StandardCopyOption.REPLACE_EXISTING); }
    private void cleanup(ActiveJob job) { job.preview.close(); try { Files.deleteIfExists(job.jobFile); } catch (IOException ignored) {} }
    private static int background(View view) { if (view.transparentBackground) return 0; try { return 0xFF000000 | Integer.parseInt(view.background.replace("#", ""),16); } catch(Exception ignored){return 0xFF000000;} }
    private static double value(Double value, double fallback) { return value == null ? fallback : value; }
    private static double effectiveBrightness(View view) {
        if (view.brightness != null) return clampBrightness(view.brightness);
        // 老版本任务没有亮度字段，保留底视图的默认补光行为。
        return view.pitch <= -80.0 ? 1.50 : 1.0;
    }
    private static double clampBrightness(double value) {
        return Math.max(0.25, Math.min(3.0, Double.isFinite(value) ? value : 1.0));
    }
    private static void resize(Path source, Path target, int width, int height) throws IOException { BufferedImage input=ImageIO.read(source.toFile());BufferedImage output=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);Graphics2D g=output.createGraphics();try{g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.drawImage(input,0,0,width,height,null);}finally{g.dispose();}ImageIO.write(output,"PNG",target.toFile()); }
    private static void event(String type, String id, double progress, String stage) { System.out.println("GPU_AGENT_JSON:" + GSON.toJson(new RuntimeEvent(type,id,progress,stage))); }

    private record RenderJob(String id, String input, String outputDirectory, List<View> views) {}
    private record View(String id, String name, double yaw, double pitch, Double zoom, Boolean autoFill,
                        int width, int height, String background, boolean transparentBackground, int supersampling,
                        Double brightness) {}
    private record ResultImage(String id, String name, int width, int height, String path) {}
    private record RenderResult(String id, boolean success, String errorCode, String error, long elapsedMillis,
                                boolean cacheHit, String gpu, List<ResultImage> images) {}
    private record RenderStatus(long timestamp, boolean ready, boolean busy, boolean inWorld, String rendererVersion,
                                String minecraftVersion, String gpu, int maxTextureSize, String resourcePackFingerprint,
                                double progress, String stage, boolean silent, boolean spectator,
                                 boolean spectatorsGenerateChunksDisabled, String renderWorld,
                                 boolean nightVisionEnabled, int nightVisionLevel) {}
    private record RuntimeEvent(String type, String id, double progress, String stage) {}
    private static final class ActiveJob { final RenderJob request;final Path jobFile;final Path output;final QuickLitematicaPreview3D.HeadlessPreview preview;final long startedAt;final List<ResultImage> images=new ArrayList<>();final java.util.Set<String> retriedViews=new java.util.HashSet<>();volatile Throwable error;boolean exporting;int viewIndex;ActiveJob(RenderJob r,Path j,Path o,QuickLitematicaPreview3D.HeadlessPreview p,long s){request=r;jobFile=j;output=o;preview=p;startedAt=s;} }
    private static final class RenderException extends Exception { final String code;RenderException(String code,String message){super(message);this.code=code;} }
}
