package dev.qqbot.gpuagent;

import dev.qqbot.standalone.PreviewEngine;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

final class RenderService implements AutoCloseable {
    private final Path root;
    private final RuntimeManager runtime;
    private final AgentConfig config;
    private final CacheStore cacheStore;
    private static final class CacheLock {
        final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        int users;
    }
    private final java.util.concurrent.ConcurrentHashMap<String, CacheLock> cacheLocks = new java.util.concurrent.ConcurrentHashMap<>();
    private final RenderTaskQueue<QueuedTask> tasks = new RenderTaskQueue<>();
    private final List<Thread> workers = new ArrayList<>();
    private final java.util.concurrent.ConcurrentHashMap<QueuedTask, RunningTask> runningTasks = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    /** 取消代次：cancelAll 递增；任务开始时记录当前代次，渲染循环发现代次变化即中止本任务。 */
    private volatile long cancelEpoch;
    /** 队列和运行中任务仍持有的投影字节数，防止无限排队保留完整 byte[]。 */
    private final AtomicLong retainedRequestBytes = new AtomicLong();
    private Consumer<RenderModels.TaskHistory> historyListener;
    private final ScheduledExecutorService idleTimer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "runtime-idle-watch");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong idleGeneration = new AtomicLong();
    private final ScheduledExecutorService timeoutTimer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "render-timeout-watch");
        thread.setDaemon(true);
        return thread;
    });
    private Consumer<String> log = ignored -> {};

    private static final class QueuedTask {
        final RenderModels.Request request;
        final byte[] schematic;
        final Duration timeout;
        final String source;
        final String historyLocation;
        final RenderModels.TaskMeta meta;
        final boolean auxiliary;
        final boolean transientOutput;
        final CompletableFuture<RenderModels.Result> future = new CompletableFuture<>();

        QueuedTask(RenderModels.Request request, byte[] schematic, Duration timeout, String source, String historyLocation,
                   RenderModels.TaskMeta meta, boolean auxiliary, boolean transientOutput) {
            this.request = request;
            this.schematic = schematic;
            this.timeout = timeout;
            this.source = source;
            this.historyLocation = historyLocation;
            this.meta = meta;
            this.auxiliary = auxiliary;
            this.transientOutput = transientOutput;
        }
    }

    /** 单个正在运行的任务的可见状态（状态栏/心跳用）。 */
    private static final class RunningTask {
        final String file;
        final String source;
        final RenderModels.TaskMeta meta;
        final int slot;
        volatile String stage = "";
        volatile Path outputDir;

        RunningTask(String file, String source, RenderModels.TaskMeta meta, int slot) {
            this.file = file; this.source = source; this.meta = meta; this.slot = slot;
        }
    }

    RenderService(Path applicationRoot, AgentConfig config) {
        this.root = applicationRoot.resolve("tasks");
        this.config = config;
        this.cacheStore = new CacheStore(applicationRoot, config, message -> log.accept(message));
        this.runtime = new RuntimeManager(applicationRoot, config);
        cleanupAbandonedTaskDirectories();
        int workerCount = Math.max(1, Math.min(4, config.maxConcurrentRenders));
        for (int index = 0; index < workerCount; index++) {
            Thread worker = new Thread(this::runLoop, "gpu-render-queue-" + (index + 1));
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }
    }

    void setLog(Consumer<String> log) {
        this.log = log == null ? ignored -> {} : log;
        this.runtime.setLog(this.log);
    }

    CompletableFuture<RenderModels.Result> submit(RenderModels.Request request, byte[] schematic, Duration timeout) {
        return submit(request, schematic, timeout, "本地", null);
    }

    CompletableFuture<RenderModels.Result> submit(RenderModels.Request request, byte[] schematic, Duration timeout,
                                                  String source, String historyLocation) {
        return submit(request, schematic, timeout, source, historyLocation, null);
    }

    CompletableFuture<RenderModels.Result> submit(RenderModels.Request request, byte[] schematic, Duration timeout,
                                                  String source, String historyLocation, RenderModels.TaskMeta meta) {
        return submit(request, schematic, timeout, source, historyLocation, meta, false, false);
    }

    CompletableFuture<RenderModels.Result> submitAuxiliary(RenderModels.Request request, byte[] schematic, Duration timeout,
                                                           String source, RenderModels.TaskMeta meta) {
        return submit(request, schematic, timeout, source, null, meta, true, false);
    }

    CompletableFuture<RenderModels.Result> submitTransient(RenderModels.Request request, byte[] schematic, Duration timeout,
                                                           String source, String historyLocation) {
        return submit(request, schematic, timeout, source, historyLocation, null, false, true);
    }

    private CompletableFuture<RenderModels.Result> submit(RenderModels.Request request, byte[] schematic, Duration timeout,
                                                          String source, String historyLocation, RenderModels.TaskMeta meta,
                                                          boolean auxiliary, boolean transientOutput) {
        CompletableFuture<RenderModels.Result> rejected = new CompletableFuture<>();
        if (request == null || schematic == null) {
            rejected.completeExceptionally(new RenderFailure("INVALID_REQUEST", "渲染请求和投影数据不能为空"));
            return rejected;
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0) {
            rejected.completeExceptionally(new RenderFailure("INVALID_TIMEOUT", "渲染超时必须为正数"));
            return rejected;
        }
        if (closed.get()) {
            rejected.completeExceptionally(new RenderFailure("CLOSED", "渲染服务已关闭"));
            return rejected;
        }
        if (tasks.isDraining()) {
            rejected.completeExceptionally(new RenderFailure("DRAINING", "渲染端正在释放内存，请稍后重试"));
            return rejected;
        }
        long requestBytes = schematic == null ? 0 : schematic.length;
        long limit = config.maxQueuedRequestBytes;
        while (true) {
            long current = retainedRequestBytes.get();
            if (limit > 0 && requestBytes > limit - current) {
                rejected.completeExceptionally(new RenderFailure("QUEUE_MEMORY_LIMIT",
                        "渲染队列已达到投影数据内存上限（" + formatBytes(limit) + "），请稍后重试"));
                return rejected;
            }
            if (retainedRequestBytes.compareAndSet(current, current + requestBytes)) break;
        }
        QueuedTask task = new QueuedTask(request, schematic, timeout, source, historyLocation, meta, auxiliary, transientOutput);
        RenderTaskQueue.OfferResult offered = tasks.offer(task);
        if (offered != RenderTaskQueue.OfferResult.ACCEPTED) {
            retainedRequestBytes.addAndGet(-requestBytes);
            rejected.completeExceptionally(new RenderFailure("CLOSED".equals(offered.name()) ? "CLOSED" : "DRAINING",
                    offered == RenderTaskQueue.OfferResult.CLOSED ? "渲染服务已关闭" : "渲染端正在释放内存，请稍后重试"));
            return rejected;
        }
        idleGeneration.incrementAndGet();
        return task.future;
    }

    void setHistoryListener(Consumer<RenderModels.TaskHistory> listener) { this.historyListener = listener; }
    String currentFile() {
        return runningTasks.values().stream().map(task -> task.file).reduce((left, right) -> left + "、" + right).orElse(null);
    }
    String currentSource() {
        return runningTasks.values().stream().map(task -> task.source).findFirst().orElse("本地");
    }
    String currentStage() {
        return runningTasks.values().stream().map(task -> task.stage).filter(stage -> stage != null && !stage.isEmpty())
                .reduce((left, right) -> left + "、" + right).orElse("");
    }
    boolean isDraining() { return tasks.isDraining(); }

    /** 内存看门狗用：进入排空状态（停止领取新任务）。 */
    void startDraining() { tasks.startDraining(); }

    /** 内存看门狗用：排空后（手头任务全部完成）要执行的回调（触发程序重启）。 */
    void setDrainCompleteListener(Runnable listener) { tasks.setDrainCompleteListener(listener); }

    /** 终止当前正在渲染的任务并清空排队中的任务。 */
    void cancelAll() {
        cancelEpoch++;
        List<QueuedTask> pending = new ArrayList<>();
        pending.addAll(tasks.drainQueued());
        for (QueuedTask task : pending) {
            task.future.completeExceptionally(new RenderFailure("CANCELLED", "任务已手动终止"));
            retainedRequestBytes.addAndGet(-task.schematic.length);
        }
        for (Thread worker : workers) if (worker.isAlive()) worker.interrupt();
        log.accept("已请求终止当前渲染任务");
        scheduleIdleStop();
    }

    private void runLoop() {
        while (!closed.get()) {
            QueuedTask task;
            try {
                task = tasks.take();
            } catch (InterruptedException interrupted) {
                if (closed.get()) return;
                continue;
            }
            if (task == null) return;
            long epochAtStart = cancelEpoch;
            int slot = Math.min(workers.indexOf(Thread.currentThread()), runtime.slotCount() - 1);
            RunningTask running = new RunningTask(task.request.filename(), task.source, task.meta, slot);
            runningTasks.put(task, running);
            long start = System.nanoTime();
            boolean success = false;
            boolean timedOut = false;
            RenderTaskTimeout guard = null;
            try {
                guard = new RenderTaskTimeout(timeoutTimer, task.timeout);
                RenderModels.Result result = render(task, running, slot, epochAtStart, guard);
                guard.close();
                if (guard.expired()) {
                    result.close();
                    throw guard.failure();
                }
                if (!task.future.complete(result)) result.close();
                success = true;
            }
            catch (Throwable throwable) {
                timedOut = guard != null && guard.expired() || throwable instanceof RenderFailure failure && "TIMEOUT".equals(failure.code);
                if (guard != null) guard.close();
                // Recovery must not inherit the timeout interruption: it waits for the child to exit.
                if (timedOut || cancelEpoch != epochAtStart) {
                    Thread.interrupted();
                    try { runtime.recoverUnresponsive(slot, task.request.filename() + "：" + (timedOut ? "渲染超时取消" : "手动取消")); }
                    catch (Throwable recoveryError) { log.accept("回收客户端失败：" + recoveryError.getMessage()); }
                }
                if (cancelEpoch != epochAtStart) task.future.completeExceptionally(new RenderFailure("CANCELLED", "任务已手动终止"));
                else if (timedOut) {
                    log.accept(task.request.filename() + "：" + guard.failure().getMessage());
                    task.future.completeExceptionally(guard.failure());
                }
                else task.future.completeExceptionally(throwable);
            }
            finally {
                try {
                    if (guard != null) guard.close();
                    runningTasks.remove(task);
                    retainedRequestBytes.addAndGet(-task.schematic.length);
                    runtime.recordRenderCompleted(slot);
                    long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
                    String status = success ? "成功" : (cancelEpoch != epochAtStart ? "已终止" : timedOut ? "超时取消" : "失败");
                    Path location = task.historyLocation != null ? Path.of(task.historyLocation)
                            : running.outputDir != null ? running.outputDir : null;
                    Consumer<RenderModels.TaskHistory> listener = historyListener;
                    if (listener != null) try {
                        listener.accept(new RenderModels.TaskHistory(task.request.filename(), task.request.views().size(),
                                elapsedMillis, status, location == null ? "" : location.toString(), task.source));
                    } catch (Throwable listenerError) { log.accept("记录渲染历史失败：" + listenerError.getMessage()); }
                } catch (RuntimeException cleanupError) {
                    log.accept("清理渲染任务状态失败：" + cleanupError.getMessage());
                } finally {
                    try { tasks.completeTask(); }
                    finally { scheduleIdleStop(); }
                }
            }
        }
    }

    /** 队列空且超过空闲时限后自动关闭 Minecraft 渲染端，避免每次渲染都冷启动。 */
    private void scheduleIdleStop() {
        int idleMillis = config.renderIdleStopMillis;
        if (closed.get()) return;
        if (idleMillis <= 0 || !runningTasks.isEmpty() || queueLength() > 0) return;
        long generation = idleGeneration.get();
        try {
            idleTimer.schedule(() -> {
                if (idleGeneration.get() != generation || !runningTasks.isEmpty() || queueLength() > 0) return;
                if (runtime.isAlive()) {
                    log.accept("渲染运行时已空闲 " + (idleMillis / 1000) + " 秒且无新任务，自动关闭 Minecraft");
                    runtime.stop();
                }
            }, idleMillis, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // close() 与任务收尾并发时，定时器已停止；无需把正常退出记录成渲染异常。
        }
    }

    boolean isBusy() { return !runningTasks.isEmpty(); }
    int queueLength() { return tasks.size(); }
    int runningTaskCount() { return tasks.inFlightCount(); }
    long retainedRequestBytes() { return retainedRequestBytes.get(); }
    RuntimeManager runtime() { return runtime; }

    /**
     * 内存看门狗用：所有已领取任务完成后，只重启 Minecraft 渲染端，保留等待中的任务和机器人连接。
     * RuntimeManager.stop() 返回前会同步回收 Java/Fabric/GLFW 进程，之后队列线程继续领取任务并按需启动新客户端。
     */
    void restartRuntimeAfterDrain() {
        if (!tasks.isDraining() || tasks.inFlightCount() != 0) return;
        try {
            log.accept("内存保护：当前任务已完成，正在停止并回收 Minecraft GPU 运行时");
            runtime.stop();
            log.accept("内存保护：Minecraft GPU 运行时已完全退出，继续处理剩余队列");
        } finally {
            tasks.resume();
        }
    }

    /** 渲染缓存目录：配置为空时使用数据目录下的 cache。 */
    Path cacheDirectory() {
        return cacheStore.directory();
    }

    int rebuildProjectionIndex() throws IOException {
        ProjectionCacheIndex.rebuild(cacheStore.directory());
        int count = ProjectionCacheIndex.read(cacheStore.directory()).size();
        log.accept("已重建投影搜索索引：" + count + " 个有效投影");
        return count;
    }

    ProjectionArchiveImporter.ImportResult importCacheArchive(Path archive) throws IOException {
        ProjectionArchiveImporter.ImportResult result = ProjectionArchiveImporter.importArchive(archive, cacheStore);
        log.accept("已导入缓存投影：新增 " + result.importedCount() + " 个，已存在 "
                + result.existingFiles() + " 个，跳过无效条目 " + result.invalidEntries() + " 个");
        return result;
    }

    void recordCacheUsage(Path entry) {
        cacheStore.recordUsage(entry);
    }

    boolean cacheIsCurrent(RenderModels.Request request, byte[] schematic) {
        return cacheStore.isCurrent(request, schematic, Main.VERSION, cacheStore.resourcePackFingerprint());
    }

    void invalidateCacheImages(byte[] schematic) {
        String fileHash = cacheStore.hash(schematic);
        if (cacheStore.invalidateImages(fileHash)) log.accept("已删除配置失效的投影图片缓存 " + fileHash + "，将重新渲染");
    }

    private RenderModels.Result render(QueuedTask queued, RunningTask running, int slot, long epochAtStart,
                                      RenderTaskTimeout guard) throws Exception {
        RenderModels.Request request = queued.request;
        byte[] schematic = queued.schematic;
        validate(request, schematic);
        String fileHash = cacheStore.hash(schematic);
        String packFingerprint = cacheStore.resourcePackFingerprint();
        CacheLock cacheLock = cacheLocks.compute(fileHash, (key, current) -> {
            CacheLock lock = current == null ? new CacheLock() : current;
            lock.users++;
            return lock;
        });
        boolean locked = false;
        boolean retainTaskDirectory = false;
        Path taskDirectory = null;
        try {
            locked = cacheLock.lock.tryLock(guard.remaining().toNanos(), TimeUnit.NANOSECONDS);
            if (!locked) throw guard.failure();
            {
                CacheStore.CacheHit cached = queued.auxiliary || queued.transientOutput ? null
                        : cacheStore.lookup(request, schematic, Main.VERSION, packFingerprint);
                if (cached != null) {
                    running.outputDir = cached.directory().toAbsolutePath().normalize();
                    log.accept("命中统一投影缓存 " + fileHash + "，无需启动 Minecraft");
                    return new RenderModels.Result(request.id(), cached.images(), 0, true, cached.gpu());
                }

                if (queued.auxiliary) {
                    cacheStore.importProjection(schematic, request.filename());
                    List<RenderModels.Image> extra = auxiliaryHit(request, fileHash, packFingerprint);
                    if (!extra.isEmpty()) return new RenderModels.Result(request.id(), extra, 0, true, "cache");
                } else if (!queued.transientOutput) {
                    cacheStore.invalidateImages(fileHash);
                }

                runtime.ensureRunning(slot, guard.remaining());
                guard.check();
                String id = UUID.randomUUID().toString();
                Path task = root.resolve(id);
                taskDirectory = task;
                Path folder = queued.transientOutput ? task.resolve("projection") : cacheStore.directory().resolve(fileHash);
                Path input = folder.resolve(CacheStore.safeProjectionFilename(request.filename()));
                if (!queued.transientOutput && Files.isRegularFile(folder.resolve("about.json5"))) {
                    // 同一哈希的首次文件名必须保持稳定，CacheStore.save 会再次确认该名称。
                    try {
                        JsonObject about = Protocol.GSON.fromJson(Files.readString(folder.resolve("about.json5")), JsonObject.class);
                        String stored = about == null || !about.has("存储投影文件名") ? "" : about.get("存储投影文件名").getAsString();
                        if (!stored.isBlank()) input = folder.resolve(stored);
                    } catch (RuntimeException ignored) { }
                }
                Files.createDirectories(folder);
                if (!Files.exists(input)) Files.write(input, schematic);
                Path output = task.resolve("output");
                // 任务输出只是运行时中转目录，最终文件统一落到配置的缓存目录。
                running.outputDir = queued.transientOutput ? output.toAbsolutePath().normalize() : folder.toAbsolutePath().normalize();
                Files.createDirectories(output);

                Path bridge = runtime.slotBridgeDirectory(slot);
                Path jobs = bridge.resolve("jobs");
                Path resultPath = bridge.resolve("results").resolve(id + ".result.json");
                Files.createDirectories(jobs);
                Files.createDirectories(resultPath.getParent());
                Files.deleteIfExists(resultPath);
                JsonObject job = new JsonObject();
                job.addProperty("id", id);
                job.addProperty("input", input.toAbsolutePath().toString());
                job.addProperty("outputDirectory", output.toAbsolutePath().toString());
                job.add("views", Protocol.GSON.toJsonTree(request.views()));
                Path temporary = jobs.resolve(id + ".job.json.tmp");
                Path target = jobs.resolve(id + ".job.json");
                Files.writeString(temporary, Protocol.GSON.toJson(job));
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                log.accept("已提交 GPU 渲染任务 " + request.filename() + "，视角数 " + request.views().size() + "（客户端 " + (slot + 1) + "）");

                long startedNanos = System.nanoTime();
                long stallMillis = Math.max(1_000L, Long.getLong("gpu.render.stallTimeoutMillis", 120_000L));
                RuntimeProgressWatch progressWatch = new RuntimeProgressWatch(
                        TimeUnit.MILLISECONDS.toNanos(stallMillis), startedNanos);
                while (!guard.expired()) {
                    if (cancelEpoch != epochAtStart) {
                        Files.deleteIfExists(target);
                        throw new RenderFailure("CANCELLED", "任务已手动终止");
                    }
                    RenderModels.RuntimeStatus statusSnapshot = runtime.currentStatus(slot);
                    running.stage = statusSnapshot != null && statusSnapshot.stage() != null ? statusSnapshot.stage() : "";
                    if (Files.exists(resultPath)) {
                        RenderModels.RuntimeResult result = Protocol.GSON.fromJson(Files.readString(resultPath), RenderModels.RuntimeResult.class);
                        Files.deleteIfExists(resultPath);
                        if (result == null || !result.success()) {
                            throw new RenderFailure(result == null ? "INVALID_RUNTIME_RESULT" : result.errorCode(),
                                    result == null ? "GPU 运行时返回无效结果" : result.error());
                        }
                        List<RenderModels.Image> images = new ArrayList<>();
                        for (RenderModels.RuntimeImage image : result.images()) {
                            Path path = Path.of(image.path()).toAbsolutePath().normalize();
                            if (!path.startsWith(output.toAbsolutePath().normalize()) || !isPng(path)) {
                                throw new RenderFailure("INVALID_IMAGE", "GPU 运行时返回了无效图片路径或内容");
                            }
                            images.add(new RenderModels.Image(image.id(), image.name(), image.width(), image.height(), path));
                        }
                        guard.check();
                        List<RenderModels.Image> completeImages = requireCompleteImages(request, images);
                        if (queued.transientOutput) {
                            retainTaskDirectory = true;
                            return new RenderModels.Result(request.id(), completeImages, result.elapsedMillis(), false,
                                    result.gpu(), taskDirectory);
                        }
                        List<RenderModels.Image> cachedImages = queued.auxiliary
                                ? saveAuxiliary(request, completeImages, fileHash, packFingerprint)
                                : cacheStore.save(request, completeImages, schematic, running.meta,
                                        running.source, Main.VERSION, packFingerprint, result.elapsedMillis(), result.gpu());
                        running.outputDir = folder.toAbsolutePath().normalize();
                        return cacheStore.result(request, cachedImages, result.elapsedMillis(), result.cacheHit(), result.gpu());
                    }
                    if (!runtime.isAlive(slot)) throw new RenderFailure("RUNTIME_CRASH", "Minecraft GPU 运行时已退出");
                    if (progressWatch.stalled(statusSnapshot, System.nanoTime())) {
                        throw new RenderFailure("RUNTIME_STALLED", "GPU 渲染进度连续 " + (stallMillis / 1000)
                                + " 秒未推进（" + running.stage + "），正在回收卡住的客户端");
                    }
                    Thread.sleep(100);
                }
                Files.deleteIfExists(target);
                throw guard.failure();
            }
        } catch (RenderFailure failure) {
            if ("RUNTIME_STALLED".equals(failure.code)) {
                runtime.recoverUnresponsive(slot, request.filename() + "：" + failure.getMessage());
            }
            if (("RUNTIME_CRASH".equals(failure.code) || "SCHEMATIC_INVALID".equals(failure.code))
                    && !isMapArtRequest(request)) {
                log.accept("Minecraft GPU 运行时异常退出，自动回退独立渲染器：" + failure.getMessage());
                guard.check();
                return renderWithStandaloneFallback(queued, running, fileHash, schematic, packFingerprint, guard);
            }
            throw failure;
        } finally {
            if (taskDirectory != null && !retainTaskDirectory) deleteTreeQuietly(taskDirectory);
            if (locked) cacheLock.lock.unlock();
            cacheLocks.computeIfPresent(fileHash, (key, current) -> --current.users == 0 ? null : current);
        }
    }

    private RenderModels.Result renderWithStandaloneFallback(QueuedTask queued, RunningTask running,
                                                               String fileHash, byte[] schematic,
                                                               String packFingerprint, RenderTaskTimeout guard) throws Exception {
        Path temporary = Files.createTempDirectory(root, "software-fallback-");
        long started = System.nanoTime();
        boolean retainTemporary = false;
        try {
            Path input = temporary.resolve(CacheStore.safeProjectionFilename(queued.request.filename()));
            Path output = temporary.resolve("output");
            Files.createDirectories(output);
            Files.write(input, schematic);
            List<Path> packs = new ArrayList<>();
            if (config.resourcePacks != null) {
                for (AgentConfig.ResourcePackEntry pack : config.resourcePacks) {
                    if (pack.enabled() && Files.isRegularFile(Path.of(pack.path()))) packs.add(Path.of(pack.path()));
                }
            }
            try (PreviewEngine engine = PreviewEngine.load(input, runtime.minecraftClientJar(), packs)) {
                List<RenderModels.Image> images = new ArrayList<>();
                for (RenderModels.View view : queued.request.views()) {
                    guard.check();
                    int renderWidth = Math.multiplyExact(view.width(), view.supersampling());
                    int renderHeight = Math.multiplyExact(view.height(), view.supersampling());
                    int renderSize = Math.max(renderWidth, renderHeight);
                    BufferedImage source = engine.renderFrame(view.yaw(), view.pitch(),
                            view.autoFill() == null || view.autoFill()
                                    ? 1.0 : (view.zoom() == null ? 1.0 : view.zoom()), renderSize);
                    BufferedImage target = FallbackImageProcessor.process(source, view);
                    Path imagePath = output.resolve(view.id() + ".png");
                    if (!ImageIO.write(target, "png", imagePath.toFile())) {
                        throw new RenderFailure("INVALID_IMAGE", "独立渲染器无法写出 PNG");
                    }
                    images.add(new RenderModels.Image(view.id(), view.id() + ".png", view.width(), view.height(), imagePath));
                }
                long elapsed = (System.nanoTime() - started) / 1_000_000L;
                guard.check();
                if (queued.transientOutput) {
                    retainTemporary = true;
                    running.outputDir = output.toAbsolutePath().normalize();
                    return new RenderModels.Result(queued.request.id(), images, elapsed, false,
                            "software-fallback", temporary);
                }
                List<RenderModels.Image> cached = queued.auxiliary
                        ? saveAuxiliary(queued.request, images, fileHash, packFingerprint)
                        : cacheStore.save(queued.request, images, schematic, running.meta,
                                "独立渲染器回退", Main.VERSION, packFingerprint, elapsed, "software-fallback");
                running.outputDir = cacheStore.directory().resolve(fileHash).toAbsolutePath().normalize();
                return cacheStore.result(queued.request, cached, elapsed, false, "software-fallback");
            }
        } finally {
            if (!retainTemporary) deleteTreeQuietly(temporary);
        }
    }

    private List<RenderModels.Image> auxiliaryHit(RenderModels.Request request, String hash, String packFingerprint)
            throws IOException {
        if (!cacheStore.shouldCacheImages(hash)) {
            cacheStore.invalidateImages(hash);
            return List.of();
        }
        List<RenderModels.Image> images = new ArrayList<>();
        for (RenderModels.View view : request.views()) {
            Path path = auxiliaryPath(hash, view, request, packFingerprint);
            if (!isPng(path)) return List.of();
            BufferedImage decoded = ImageIO.read(path.toFile());
            if (decoded == null) return List.of();
            images.add(new RenderModels.Image(view.id(), path.getFileName().toString(),
                    decoded.getWidth(), decoded.getHeight(), path));
            decoded.flush();
        }
        cacheStore.recordUsage(cacheStore.directory().resolve(hash));
        return images;
    }

    private List<RenderModels.Image> saveAuxiliary(RenderModels.Request request, List<RenderModels.Image> images,
                                                   String hash, String packFingerprint) throws IOException {
        List<RenderModels.Image> saved = new ArrayList<>();
        cacheStore.recordUsage(cacheStore.directory().resolve(hash));
        boolean persist = cacheStore.shouldCacheImages(hash);
        Path transientDirectory = persist ? null : cacheStore.createTransientDirectory();
        try {
            for (RenderModels.Image image : images) {
                RenderModels.View view = request.views().stream().filter(candidate -> candidate.id().equals(image.id()))
                        .findFirst().orElseThrow(() -> new IOException("额外视图 ID 不匹配"));
                Path target = persist ? auxiliaryPath(hash, view, request, packFingerprint) : transientDirectory.resolve(view.id() + ".png");
                Files.createDirectories(target.getParent());
                Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
                Files.copy(image.path(), temporary, StandardCopyOption.REPLACE_EXISTING);
                try { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
                saved.add(new RenderModels.Image(image.id(), target.getFileName().toString(), image.width(), image.height(), target));
            }
            return saved;
        } catch (IOException | RuntimeException | Error failure) {
            RenderModels.deleteTemporaryDirectory(transientDirectory);
            throw failure;
        }
    }

    private Path auxiliaryPath(String hash, RenderModels.View view, RenderModels.Request request, String packFingerprint) {
        String fingerprint = cacheStore.configurationFingerprint(request, Main.VERSION, packFingerprint);
        return cacheStore.directory().resolve(hash).resolve("extra-" + view.id() + "-" + fingerprint + ".png");
    }

    private static List<RenderModels.Image> requireCompleteImages(RenderModels.Request request, List<RenderModels.Image> images) throws RenderFailure {
        if (images.size() != request.views().size() || !images.stream().map(RenderModels.Image::id).collect(java.util.stream.Collectors.toSet())
                .equals(request.views().stream().map(RenderModels.View::id).collect(java.util.stream.Collectors.toSet())))
            throw new RenderFailure("INVALID_IMAGE", "GPU 返回的图片不完整");
        return images;
    }

    private static boolean isMapArtRequest(RenderModels.Request request) {
        return request.views().size() == 1 && "extra-map-art".equals(request.views().getFirst().id());
    }

    /** 清理上次异常退出遗留的运行时中转图片，避免 C 盘任务目录长期累积。 */
    private void cleanupAbandonedTaskDirectories() {
        try {
            Files.createDirectories(root);
            try (var stream = Files.list(root)) {
                for (Path child : stream.toList()) deleteTreeQuietly(child);
            }
        } catch (IOException error) {
            log.accept("清理旧渲染中转目录失败：" + error.getMessage());
        }
    }

    private static void deleteTreeQuietly(Path path) {
        try {
            if (!Files.exists(path)) return;
            try (var stream = Files.walk(path)) {
                for (Path item : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(item);
                }
            }
        } catch (IOException ignored) { }
    }

    private static void validate(RenderModels.Request request, byte[] schematic) throws RenderFailure {
        if (request == null || request.version() != 2 || request.views() == null || request.views().isEmpty()) {
            throw new RenderFailure("INVALID_REQUEST", "渲染请求格式无效");
        }
        if (schematic.length == 0) throw new RenderFailure("INVALID_SCHEMATIC", "投影文件为空");
        for (RenderModels.View view : request.views()) {
            if (view.id() == null || !view.id().matches("[A-Za-z0-9._-]{1,80}")) {
                throw new RenderFailure("INVALID_VIEW", "视角 ID 无效");
            }
            if (view.width() <= 0 || view.height() <= 0 || view.supersampling() <= 0) {
                throw new RenderFailure("INVALID_VIEW", "视角尺寸或超采样无效");
            }
            try {
                long captureWidth = Math.multiplyExact((long) view.width(), view.supersampling());
                long captureHeight = Math.multiplyExact((long) view.height(), view.supersampling());
                Math.multiplyExact(captureWidth, captureHeight);
            }
            catch (ArithmeticException overflow) { throw new RenderFailure("OUTPUT_TOO_LARGE", "输出尺寸超出 Java 数组边界"); }
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(data));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static boolean isPng(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) < 8) return false;
        byte[] header;
        try (var input = Files.newInputStream(path)) { header = input.readNBytes(8); }
        return java.util.Arrays.equals(header, new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
    }

    @Override public void close() {
        closed.set(true);
        timeoutTimer.shutdownNow();
        idleTimer.shutdownNow();
        List<QueuedTask> pending = tasks.close();
        for (QueuedTask task : pending) {
            task.future.completeExceptionally(new RenderFailure("CLOSED", "渲染服务已关闭"));
            retainedRequestBytes.addAndGet(-task.schematic.length);
        }
        for (Thread worker : workers) worker.interrupt();
        for (Thread worker : workers) {
            if (worker == Thread.currentThread()) continue;
            try { worker.join(5_000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        runtime.close();
    }

    private static String formatBytes(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.0f MB", bytes / 1024.0 / 1024.0);
    }

    static final class RenderFailure extends Exception {
        final String code;
        RenderFailure(String code, String message) { super(message == null ? code : message); this.code = code; }
    }
}
