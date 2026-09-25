package dev.qqbot.gpuagent;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.function.Consumer;

/** 将 Agent 和内置 Minecraft 运行时的非正常退出写入可导出的诊断日志。 */
final class CrashLogger {
    private static final Object LOCK = new Object();
    private static volatile Path root;
    private static volatile Consumer<String> reporter = ignored -> {};

    private CrashLogger() {}

    static void install(Path logRoot, Consumer<String> log) {
        root = logRoot.toAbsolutePath().normalize();
        reporter = log == null ? ignored -> {} : log;
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> record(
                "Agent 未捕获异常（线程 " + thread.getName() + ")", error));
    }

    static void runtimeExit(Path logRoot, int slot, int exitCode) {
        if (exitCode != 0) {
            recordAt(logRoot, "runtime-crash.log",
                    "Minecraft GPU 运行时异常退出（客户端 " + (slot + 1) + "，退出码 " + exitCode + ")", null);
        }
    }

    static void record(String message, Throwable error) {
        recordAt(root, "agent-crash.log", message, error);
        try { reporter.accept(message + (error == null ? "" : "：" + error.getMessage())); }
        catch (Throwable ignored) {}
    }

    private static void recordAt(Path logRoot, String fileName, String message, Throwable error) {
        if (logRoot == null) return;
        StringBuilder text = new StringBuilder()
                .append(LocalDateTime.now()).append("  ").append(message == null ? "" : message).append(System.lineSeparator());
        if (error != null) {
            StringWriter stack = new StringWriter();
            error.printStackTrace(new PrintWriter(stack));
            text.append(stack).append(System.lineSeparator());
        }
        synchronized (LOCK) {
            try {
                Files.createDirectories(logRoot);
                Files.writeString(logRoot.resolve(fileName), text,
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception ignored) {}
        }
    }
}
