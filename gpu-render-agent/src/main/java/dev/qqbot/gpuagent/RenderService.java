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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

final class RenderService implements AutoCloseable {
    private final Path root;
    private final RuntimeManager runtime;
    private final AgentConfig config;
    private final LinkedBlockingQueue<QueuedTask> tasks = new LinkedBlockingQueue<>();
    private final List<Thread> workers = new ArrayList<>();
    private final java.util.concurrent.ConcurrentHashMap<QueuedTask, RunningTask> runningTasks = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    /** 取消代次：cancelAll 递增；任务开始时记录当前代次，渲染循环发现代次变化即中止本任务。 */
    private volatile long cancelEpoch;
    /** 内存保护排空状态：true 时不再领取新任务，手头任务照常完成。 */
    private volatile boolean draining;
    private volatile Runnable drainCompleteListener;
    private volatile int queueLength;
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
        idleGeneration.incrementAndGet();
        QueuedTask task = new QueuedTask(request, schematic, timeout, source, historyLocation, meta);
        queueLength++;
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
        if (draining && runningTasks.isEmpty() && drainCompleteListener != null) {
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
            queueLength = Math.max(0, queueLength - 1);
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
                queueLength = Math.max(0, queueLength - 1);
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
        if (idleMillis <= 0 || !runningTasks.isEmpty() || queueLength > 0) return;
        long generation = idleGeneration.get();
        idleTimer.schedule(() -> {
            if (idleGeneration.get() != generation || !runningTasks.isEmpty() || queueLength > 0) return;
            if (runtime.isAlive()) {
                log.accept("渲染运行时已空闲 " + (idleMillis / 1000) + " 秒且无新任务，自动关闭 Minecraft");
                runtime.stop();
            }
        }, idleMillis, TimeUnit.MILLISECONDS);
    }

    boolean isBusy() { return !runningTasks.isEmpty(); }
    int queueLength() { return queueLength; }
    int runningTaskCount() { return runningTasks.size(); }
    RuntimeManager runtime() { return runtime; }

    /** 渲染缓存目录：配置为空时使用数据目录下的 cache。 */
    Path cacheDirectory() {
        if (config.cacheDirectory != null && !config.cacheDirectory.isBlank()) return Path.of(config.cacheDirectory);
        return root.getParent().resolve("cache");
    }

    /**
     * 渲染成功后的缓存动作：
     * 1) 图片按 PNG 内容哈希去重保存到 cache/images/&lt;hash&gt;.png（同图不重复存储）；
     * 2) 在 cache/render-records.jsonl 追加一行记录（时间/群号/发送人/分辨率/文件大小/别名）；
     * 3) 别名 = 投影名-时间戳（到秒），仅记录在案，不做第二份物理拷贝；
     * 4) cacheKeepProjections=false 时删除该投影的缓存输入文件；
     * 5) 缓存总量超过 cacheMaxBytes 时按最旧优先清理。
     */
    /** 定位投影的专属文件夹：同一内容哈希复用首次渲染创建的文件夹（index.json 记录映射）。 */
    private Path projectionFolder(Path cacheDir, String fileHash, String filename) throws IOException {
        Files.createDirectories(cacheDir);
        Path indexFile = cacheDir.resolve("index.json");
        java.util.Map<String, String> index = new java.util.LinkedHashMap<>();
        if (Files.exists(indexFile)) {
            java.util.Map<String, String> loaded = Protocol.GSON.fromJson(Files.readString(indexFile),
                    new com.google.gson.reflect.TypeToken<java.util.LinkedHashMap<String, String>>() { }.getType());
            if (loaded != null) index.putAll(loaded);
        }
        String folderName = index.get(fileHash);
        if (folderName == null) {
            String timestamp = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MMdd-HHmmss"));
            folderName = sanitizeFolderName(filename) + "-" + timestamp;
            index.put(fileHash, folderName);
            Files.writeString(indexFile, Protocol.GSON.toJson(index));
        }
        Path folder = cacheDir.resolve(dev.qqbot.gpuagent.Main.VERSION).resolve(folderName);
        Files.createDirectories(folder);
        return folder;
    }

    /**
     * 渲染成功后的落盘（新版目录结构）：
     * <缓存根>/<工具版本>/<投影名-首次渲染日期到秒>/ 下包含：
     *   投影原文件（保持原名，cacheKeepProjections=false 时不保存）、
     *   正-<时间戳>.png / 反-<时间戳>.png（保持渲染分辨率）、
     *   记录.json5（每次渲染追加一条，中文字段+中文备注）。
     * 同一投影（内容哈希相同）复用首次渲染创建的文件夹，通过 index.json 定位。
     */
    private void saveRenderOutputs(RenderModels.Request request, List<RenderModels.Image> images,
                                   byte[] schematic, RenderModels.TaskMeta meta, String source, Path folder, String fileHash, Path legacyInput) {
        try {
            Path cacheDir = cacheDirectory();

            Files.createDirectories(folder);

            // 投影文件（保持原名）
            Path projectionFile = folder.resolve(request.filename());
            if (config.cacheKeepProjections) {
                if (!Files.exists(projectionFile)) Files.write(projectionFile, schematic);
            } else {
                Files.deleteIfExists(projectionFile);
            }
            // 旧的 inputs 拷贝不再需要
            Files.deleteIfExists(legacyInput);

            // 渲染图：正/反 + 时间戳到秒
            String timestamp = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MMdd-HHmmss"));
            String zheng = null, fan = null;
            for (var image : images) {
                String name = ("isometric-reverse".equals(image.id()) || image.name().contains("reverse")) ? "反-" : "正-";
                Path target = folder.resolve(name + timestamp + ".png");
                Files.copy(image.path(), target, StandardCopyOption.REPLACE_EXISTING);
                if (name.startsWith("反")) fan = target.getFileName().toString(); else zheng = target.getFileName().toString();
            }

            // 记录.json5：中文字段 + 中文备注，每次渲染追加一条
            String group = meta == null || meta.group() == null || meta.group().isBlank() ? "-" : meta.group();
            String user = meta == null || meta.user() == null || meta.user().isBlank() ? "-" : meta.user();
            String resolution = images.isEmpty() ? "-" : images.get(0).width() + "x" + images.get(0).height();
            String zhengName = zheng == null ? "-" : zheng;
            String fanName = fan == null ? "-" : fan;
            StringBuilder entry = new StringBuilder();
            entry.append("  \"" + timestamp + "\": {\n");
            entry.append("    // 投影文件名\n");
            entry.append("    \"投影名称\": " + json5String(request.filename()) + ",\n");
            entry.append("    // 来源群号（官方机器人返回的是哈希标识）\n");
            entry.append("    \"群号\": " + json5String(group) + ",\n");
            entry.append("    // 发送人（官方机器人返回的是哈希标识）\n");
            entry.append("    \"发送人\": " + json5String(user) + ",\n");
            entry.append("    // 图片分辨率（宽x高，由工具分辨率设置决定）\n");
            entry.append("    \"分辨率\": " + json5String(resolution) + ",\n");
            entry.append("    // 投影文件大小（字节）\n");
            entry.append("    \"投影大小\": " + schematic.length + ",\n");
            entry.append("    // 投影文件哈希（SHA-256）\n");
            entry.append("    \"投影哈希\": " + json5String(fileHash) + ",\n");
            entry.append("    // 渲染图片文件名\n");
            entry.append("    \"渲染图\": [" + json5String(zhengName) + ", " + json5String(fanName) + "],\n");
            entry.append("    // 任务来源（云端/本地/HTTP）\n");
            entry.append("    \"来源\": " + json5String(source) + ",\n");
            entry.append("    // 渲染状态\n");
            entry.append("    \"状态\": \"成功\"\n");
            entry.append("  }");
            appendRecord(folder.resolve("记录.json5"), entry.toString());

            evictCacheIfNeeded(cacheDir);
        } catch (Throwable error) {
            log.accept("缓存写入失败（不影响渲染结果）：" + error);
            debug("saveRenderOutputs 异常: " + error);
        }
    }

    private static void debug(String line) {
        try {
            java.nio.file.Path file = java.nio.file.Path.of(System.getenv("LOCALAPPDATA"), "LitematicGpuAgent", "cache-debug.log");
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, java.time.LocalDateTime.now() + " " + line + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

    /** 在 JSON5 对象的最后一个 } 前插入一条渲染记录（文件不存在则创建骨架）。 */
    private static void appendRecord(Path file, String entryText) throws IOException {
        if (Files.exists(file)) {
            String text = Files.readString(file);
            int close = text.lastIndexOf('}');
            if (close >= 0) {
                text = text.substring(0, close) + ",\n" + entryText + "\n}";
                Files.writeString(file, text);
                return;
            }
        }
        String skeleton = "// Litematic 渲染记录（JSON5：支持注释，可直接用文本编辑器查看）\n// 键为渲染时间（yyyy-MMdd-HHmmss）\n{\n" + entryText + "\n}\n";
        Files.writeString(file, skeleton);
    }

    private static String json5String(String value) {
        if (value == null) return "\"-\"";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String sanitizeFolderName(String filename) {
        String stem = filename.endsWith(".litematic") ? filename.substring(0, filename.length() - ".litematic".length()) : filename;
        String cleaned = stem.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\p{Cntrl}", "").trim();
        if (cleaned.length() > 60) cleaned = cleaned.substring(0, 60);
        return cleaned.isEmpty() ? "未命名" : cleaned;
    }

    /** 缓存总量（版本文件夹内全部文件）超过上限时，按修改时间最旧优先删除，直到回到上限以内。 */
    private void evictCacheIfNeeded(Path cacheDir) {
        try {
            long max = Math.max(0, config.cacheMaxBytes);
            if (max <= 0) return;
            List<Path> files = new ArrayList<>();
            try (var stream = Files.walk(cacheDir)) {
                for (Path file : stream.toList()) {
                    if (Files.isRegularFile(file) && !"index.json".equals(file.getFileName().toString())) files.add(file);
                }
            }
            long total = 0;
            for (Path file : files) total += Files.size(file);
            if (total <= max) return;
            files.sort(java.util.Comparator.comparingLong(RenderService::modifiedMillis));
            for (Path file : files) {
                if (total <= max) break;
                long size = Files.size(file);
                Files.deleteIfExists(file);
                total -= size;
            }
            log.accept("缓存超过上限，已清理最旧文件，当前约 " + (total / 1024 / 1024) + " MB");
        } catch (Throwable error) {
            log.accept("缓存清理失败：" + error.getMessage());
        }
    }

    private static void collect(Path dir, List<Path> out) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            for (Path file : stream.toList()) {
                if (Files.isRegularFile(file)) out.add(file);
            }
        }
    }

    private static long modifiedMillis(Path file) {
        try { return Files.getLastModifiedTime(file).toMillis(); }
        catch (IOException ignored) { return 0L; }
    }

    private RenderModels.Result render(QueuedTask queued, RunningTask running, int slot, long epochAtStart) throws Exception {
        RenderModels.Request request = queued.request;
        byte[] schematic = queued.schematic;
        Duration timeout = queued.timeout;
        validate(request, schematic);
        runtime.ensureRunning(slot, timeout);
        String id = UUID.randomUUID().toString();
        Path task = root.resolve(id);
        // 输入按内容哈希寻址：同一投影的路径保持稳定，渲染 mod 的网格缓存才能跨任务命中
        //（否则云端任务每次落在随机 UUID 路径上，缓存永不命中，每张图都要重建网格数分钟）。
        Path cacheDir = cacheDirectory();
        String fileHash = sha256Hex(schematic);
        Path folder = projectionFolder(cacheDir, fileHash, request.filename());
        Path input = folder.resolve(request.filename());
        Path legacyInput = cacheDir.resolve("inputs").resolve(fileHash + ".litematic");
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
                RenderModels.Result successResult = new RenderModels.Result(request.id(), images, result.elapsedMillis(), result.cacheHit(), result.gpu());
                saveRenderOutputs(request, images, schematic, running.meta, running.source, folder, fileHash, legacyInput);
                return successResult;
            }
            Thread.sleep(100);
        }
        Files.deleteIfExists(target);
        throw new RenderFailure("TIMEOUT", "GPU 渲染超过 " + timeout.toSeconds() + " 秒");
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
        tasks.clear();
        for (Thread worker : workers) worker.interrupt();
        runtime.close();
    }

    static final class RenderFailure extends Exception {
        final String code;
        RenderFailure(String code, String message) { super(message == null ? code : message); this.code = code; }
    }
}
