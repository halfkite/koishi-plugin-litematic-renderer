package dev.qqbot.gpuagent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ProjectionVideoExporter {
    private static final long MAX_FRAME_STORAGE_BYTES = 4L * 1024 * 1024 * 1024;
    private static final Pattern FRAME_PROGRESS = Pattern.compile("^frame=(\\d+)$");

    private ProjectionVideoExporter() {}

    static void validateSettings(int width, int height, int durationSeconds, int framesPerSecond) {
        if (width < 320 || width > 1920 || height < 180 || height > 1080 || width % 2 != 0 || height % 2 != 0)
            throw new IllegalArgumentException("MP4 宽高需为偶数，范围分别是 320-1920 和 180-1080");
        if (durationSeconds < 1 || durationSeconds > 60)
            throw new IllegalArgumentException("MP4 时长需为 1-60 秒");
        if (framesPerSecond < 1 || framesPerSecond > 60)
            throw new IllegalArgumentException("MP4 帧率需为 1-60 FPS");
        long frames = Math.multiplyExact((long) durationSeconds, framesPerSecond);
        long bytes = Math.multiplyExact(Math.multiplyExact((long) width, height), Math.multiplyExact(frames, 4L));
        if (bytes > MAX_FRAME_STORAGE_BYTES)
            throw new IllegalArgumentException("视频帧临时空间估算超过 4 GB，请降低时长、帧率或分辨率");
    }

    static int frameCount(int durationSeconds, int framesPerSecond) {
        if (durationSeconds < 1 || durationSeconds > 60 || framesPerSecond < 1 || framesPerSecond > 60)
            throw new IllegalArgumentException("MP4 时长需为 1-60 秒，帧率需为 1-60 FPS");
        return Math.multiplyExact(durationSeconds, framesPerSecond);
    }

    static List<RenderModels.View> orbitViews(int width, int height, int frameCount) {
        if (frameCount < 1) throw new IllegalArgumentException("视频至少需要一帧");
        List<RenderModels.View> views = new ArrayList<>(frameCount);
        for (int frame = 0; frame < frameCount; frame++) {
            String id = String.format(Locale.ROOT, "frame-%05d", frame);
            double yaw = 135.0 + 360.0 * frame / frameCount;
            views.add(new RenderModels.View(id, id, yaw, 36.0, 1.0, false, width, height,
                    "#000000", false, 1, 1.0));
        }
        return List.copyOf(views);
    }

    static Path encode(String executable, Path frameDirectory, int frameCount, int framesPerSecond,
                       Path output, Supplier<Boolean> cancelled, Consumer<Process> processListener,
                       Consumer<String> progress) throws IOException, InterruptedException {
        if (frameCount < 1 || framesPerSecond < 1) throw new IllegalArgumentException("视频帧数或帧率无效");
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        Path partial = output.resolveSibling(output.getFileName() + ".partial.mp4");
        Files.deleteIfExists(partial);
        Path inputPattern = frameDirectory.toAbsolutePath().normalize().resolve("frame-%05d.png");
        List<String> command = List.of(
                executable,
                "-hide_banner", "-loglevel", "error", "-nostats", "-y",
                "-framerate", Integer.toString(framesPerSecond),
                "-start_number", "0", "-i", inputPattern.toString(),
                "-frames:v", Integer.toString(frameCount), "-an",
                "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p",
                "-movflags", "+faststart", "-r", Integer.toString(framesPerSecond),
                "-progress", "pipe:1", partial.toAbsolutePath().toString());
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException error) {
            throw new IOException("无法启动 FFmpeg。请安装 FFmpeg 并加入 PATH，或在渲染设置中指定 ffmpeg.exe：" + error.getMessage(), error);
        }
        if (processListener != null) processListener.accept(process);
        ArrayDeque<String> recentOutput = new ArrayDeque<>();
        long timeoutNanos = Duration.ofMillis(Math.max(120_000L, Math.multiplyExact((long) frameCount, 2_000L))).toNanos();
        long deadline = System.nanoTime() + timeoutNanos;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            while (true) {
                if (cancelled != null && Boolean.TRUE.equals(cancelled.get())) {
                    process.destroyForcibly();
                    throw new CancellationException("MP4 导出已取消");
                }
                if (System.nanoTime() - deadline >= 0) {
                    process.destroyForcibly();
                    throw new IOException("FFmpeg 编码超时");
                }
                if (reader.ready()) {
                    String line = reader.readLine();
                    if (line == null) break;
                    if (recentOutput.size() == 24) recentOutput.removeFirst();
                    recentOutput.addLast(line);
                    Matcher matcher = FRAME_PROGRESS.matcher(line.trim());
                    if (matcher.matches() && progress != null) {
                        int frame = Math.min(frameCount, Integer.parseInt(matcher.group(1)));
                        progress.accept("MP4 编码 " + frame + "/" + frameCount + "（" + (frame * 100 / frameCount) + "%）");
                    }
                } else if (!process.isAlive()) {
                    while (reader.ready()) {
                        String line = reader.readLine();
                        if (line == null) break;
                        if (recentOutput.size() == 24) recentOutput.removeFirst();
                        recentOutput.addLast(line);
                    }
                    break;
                } else {
                    Thread.sleep(100);
                }
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor();
            }
            if (cancelled != null && Boolean.TRUE.equals(cancelled.get()))
                throw new CancellationException("MP4 导出已取消");
            if (process.exitValue() != 0)
                throw new IOException("FFmpeg 编码失败（退出码 " + process.exitValue() + "）：" + String.join(" | ", recentOutput));
            if (!Files.isRegularFile(partial) || Files.size(partial) == 0)
                throw new IOException("FFmpeg 未生成有效 MP4 文件");
            try {
                Files.move(partial, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(partial, output, StandardCopyOption.REPLACE_EXISTING);
            }
            if (progress != null) progress.accept("MP4 编码完成");
            return output;
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            throw interrupted;
        } finally {
            if (processListener != null) processListener.accept(null);
            if (process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(partial);
        }
    }

    static String safeOutputFileName(String projectionName) {
        String name = projectionName == null ? "projection" : projectionName;
        if (name.toLowerCase(Locale.ROOT).endsWith(".litematic"))
            name = name.substring(0, name.length() - ".litematic".length());
        name = name.replaceAll("[\\x00-\\x1f<>:\"/\\\\|?*]", "_").replaceAll("[ .]+$", "");
        if (name.isBlank()) name = "projection";
        if (name.matches("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$")) name = "_" + name;
        return name + ".mp4";
    }
}
