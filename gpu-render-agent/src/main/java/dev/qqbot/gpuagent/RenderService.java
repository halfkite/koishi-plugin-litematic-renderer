package dev.qqbot.gpuagent;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

final class RenderService implements AutoCloseable {
    private final Path root;
    private final RuntimeManager runtime;
    private final AgentConfig config;
    private final CacheStore cacheStore;
    private final java.util.concurrent.ConcurrentHashMap<String, Object> cacheLocks = new java.util.concurrent.ConcurrentHashMap<>();
    private final LinkedBlockingQueue<QueuedTask> tasks = new LinkedBlockingQueue<>();
    private final List<Thread> workers = new ArrayList<>();
    private final java.util.concurrent.ConcurrentHashMap<QueuedTask, RunningTask> runningTasks = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    /** 取消代次：cancelAll 递增；任务开始时记录当前代次，渲染循环发现代次变化即中止本任务。 */
    private volatile long cancelEpoch;
    /** 内存保护排空状态：true 时不再领取新任务，手头任务照常完成。 */
    private volatile boolean draining;
    private volatile Runnable drainCompleteListener;
    private final AtomicInteger queueLength = new AtomicInteger();
    /** 已经领取但尚未完成的任务数，覆盖从 take() 到 runningTasks.put() 之间的竞态窗口。 */
    private final AtomicInteger inFlightTasks = new AtomicInteger();
    /** 队列和运行中任务仍持有的投影字节数，防止无限排队保留完整 byte[]。 */
    private final AtomicLong retainedRequestBytes = new AtomicLong();
    private Consumer<RenderModels.TaskHistory> historyListener;
    private final ScheduledExecutorService idleTimer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "runtime-idle-watch");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong idleGeneration = new AtomicLong();
    private Consumer<String> log = ignored -> {};

    private static final class QueuedTask {
        final RenderModels.Request request;
        final byte[] schematic;
        final Duration timeout;
        final String source;
        final String historyLocation;
        final RenderModels.TaskMeta meta;
        final CompletableFuture<RenderModels.Result> future = new CompletableFuture<>();

        QueuedTask(RenderModels.Request request, byte[] schematic, Duration timeout, String source, String historyLocation, RenderModels.TaskMeta meta) {
            this.request = request;
            this.schematic = schematic;
            this.timeout = timeout;
            this.source = source;
            this.historyLocation = historyLocation;
            this.meta = meta;
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
        CompletableFuture<RenderModels.Result> rejected = new CompletableFuture<>();
        if (closed.get()) {
            rejected.completeExceptionally(new RenderFailure("CLOSED", "渲染服务已关闭"));
            return rejected;
        }
        if (draining) {
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
        idleGeneration.incrementAndGet();
        QueuedTask task = new QueuedTask(request, schematic, timeout, source, historyLocation, meta);
        queueLength.incrementAndGet();
        tasks.add(task);
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
    boolean isDraining() { return draining; }

    /** 内存看门狗用：进入排空状态（停止领取新任务）。 */
    void startDraining() { draining = true; maybeNotifyDrainComplete(); }

    /** 内存看门狗用：排空后（手头任务全部完成）要执行的回调（触发程序重启）。 */
    void setDrainCompleteListener(Runnable listener) { this.drainCompleteListener = listener; maybeNotifyDrainComplete(); }

    private void maybeNotifyDrainComplete() {
        if (draining && inFlightTasks.get() == 0 && drainCompleteListener != null) {
            Runnable listener = drainCompleteListener;
            drainCompleteListener = null;
            Thread.startVirtualThread(listener);
        }
    }

    /** 终止当前正在渲染的任务并清空排队中的任务。 */
    void cancelAll() {
        cancelEpoch++;
        List<QueuedTask> pending = new ArrayList<>();
        tasks.drainTo(pending);
        for (QueuedTask task : pending) {
            task.future.completeExceptionally(new RenderFailure("CANCELLED", "任务已手动终止"));
            queueLength.updateAndGet(value -> Math.max(0, value - 1));
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
                while (draining && !closed.get()) Thread.sleep(300);
                task = tasks.take();
            } catch (InterruptedException interrupted) {
                if (closed.get()) return;
                continue;
            }
            inFlightTasks.incrementAndGet();
            long epochAtStart = cancelEpoch;
            int slot = Math.min(workers.indexOf(Thread.currentThread()), runtime.slotCount() - 1);
            RunningTask running = new RunningTask(task.request.filename(), task.source, task.meta, slot);
            runningTasks.put(task, running);
            long start = System.nanoTime();
            boolean success = false;
            try {
                task.future.complete(render(task, running, slot, epochAtStart));
                success = true;
            }
            catch (Throwable throwable) {
                if (cancelEpoch != epochAtStart) task.future.completeExceptionally(new RenderFailure("CANCELLED", "任务已手动终止"));
                else task.future.completeExceptionally(throwable);
            }
            finally {
                runningTasks.remove(task);
                queueLength.updateAndGet(value -> Math.max(0, value - 1));
                retainedRequestBytes.addAndGet(-task.schematic.length);
                inFlightTasks.decrementAndGet();
                long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
                String status = success ? "成功" : (cancelEpoch != epochAtStart ? "已终止" : "失败");
                Path location = task.historyLocation != null ? Path.of(task.historyLocation)
                        : running.outputDir != null ? running.outputDir : null;
                Consumer<RenderModels.TaskHistory> listener = historyListener;
                if (listener != null) {
                    listener.accept(new RenderModels.TaskHistory(task.request.filename(), task.request.views().size(),
                            elapsedMillis, status, location == null ? "" : location.toString(), task.source));
                }
                scheduleIdleStop();
                maybeNotifyDrainComplete();
            }
        }
    }

    /** 队列空且超过空闲时限后自动关闭 Minecraft 渲染端，避免每次渲染都冷启动。 */
    private void scheduleIdleStop() {
        int idleMillis = config.renderIdleStopMillis;
        if (closed.get()) return;
        if (idleMillis <= 0 || !runningTasks.isEmpty() || queueLength.get() > 0) return;
        long generation = idleGeneration.get();
        try {
            idleTimer.schedule(() -> {
                if (idleGeneration.get() != generation || !runningTasks.isEmpty() || queueLength.get() > 0) return;
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
    int queueLength() { return queueLength.get(); }
    int runningTaskCount() { return inFlightTasks.get(); }
    long retainedRequestBytes() { return retainedRequestBytes.get(); }
    RuntimeManager runtime() { return runtime; }

    /**
     * 内存看门狗用：所有已领取任务完成后，只重启 Minecraft 渲染端，保留等待中的任务和机器人连接。
     * RuntimeManager.stop() 返回前会同步回收 Java/Fabric/GLFW 进程，之后队列线程继续领取任务并按需启动新客户端。
     */
    void restartRuntimeAfterDrain() {
        if (!draining || inFlightTasks.get() != 0) return;
        try {
            log.accept("内存保护：当前任务已完成，正在停止并回收 Minecraft GPU 运行时");
            runtime.stop();
            log.accept("内存保护：Minecraft GPU 运行时已完全退出，继续处理剩余队列");
        } finally {
            draining = false;
            maybeNotifyDrainComplete();
        }
    }

    /** 渲染缓存目录：配置为空时使用数据目录下的 cache。 */
    Path cacheDirectory() {
        return cacheStore.directory();
    }

    private RenderModels.Result render(QueuedTask queued, RunningTask running, int slot, long epochAtStart) throws Exception {
        RenderModels.Request request = queued.request;
        byte[] schematic = queued.schematic;
        Duration timeout = queued.timeout;
        validate(request, schematic);
        String fileHash = cacheStore.hash(schematic);
        String packFingerprint = cacheStore.resourcePackFingerprint();
        Object cacheLock = cacheLocks.computeIfAbsent(fileHash, ignored -> new Object());
        try {
            synchronized (cacheLock) {
                CacheStore.CacheHit cached = cacheStore.lookup(request, schematic, Main.VERSION, packFingerprint);
                if (cached != null) {
                    running.outputDir = cached.directory().toAbsolutePath().normalize();
                    log.accept("命中统一投影缓存 " + fileHash + "，无需启动 Minecraft");
                    return new RenderModels.Result(request.id(), cached.images(), 0, true, cached.gpu());
                }

                runtime.ensureRunning(slot, timeout);
                String id = UUID.randomUUID().toString();
                Path task = root.resolve(id);
                Path folder = cacheStore.directory().resolve(fileHash);
                Path input = folder.resolve(CacheStore.safeProjectionFilename(request.filename()));
                if (Files.isRegularFile(folder.resolve("about.json5"))) {
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
                running.outputDir = output.toAbsolutePath().normalize();
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

                long deadline = System.nanoTime() + timeout.toNanos();
                while (System.nanoTime() < deadline) {
                    if (cancelEpoch != epochAtStart) {
                        Files.deleteIfExists(target);
                        throw new RenderFailure("CANCELLED", "任务已手动终止");
                    }
                    RenderModels.RuntimeStatus statusSnapshot = runtime.currentStatus(slot);
                    running.stage = statusSnapshot != null && statusSnapshot.stage() != null ? statusSnapshot.stage() : "";
                    if (!runtime.isAlive(slot)) throw new RenderFailure("RUNTIME_CRASH", "Minecraft GPU 运行时已退出");
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
                        List<RenderModels.Image> cachedImages = cacheStore.save(request, images, schematic, running.meta,
                                running.source, Main.VERSION, packFingerprint, result.elapsedMillis(), result.gpu());
                        return new RenderModels.Result(request.id(), cachedImages, result.elapsedMillis(), result.cacheHit(), result.gpu());
                    }
                    Thread.sleep(100);
                }
                Files.deleteIfExists(target);
                throw new RenderFailure("TIMEOUT", "GPU 渲染超过 " + timeout.toSeconds() + " 秒");
            }
        } finally {
            cacheLocks.remove(fileHash, cacheLock);
        }
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
        idleTimer.shutdownNow();
        List<QueuedTask> pending = new ArrayList<>();
        tasks.drainTo(pending);
        for (QueuedTask task : pending) {
            task.future.completeExceptionally(new RenderFailure("CLOSED", "渲染服务已关闭"));
            queueLength.updateAndGet(value -> Math.max(0, value - 1));
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
