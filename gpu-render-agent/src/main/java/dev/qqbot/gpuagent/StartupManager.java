package dev.qqbot.gpuagent;

import java.io.IOException;
import java.nio.file.Path;

final class StartupManager {
    private StartupManager() {}

    static void setEnabled(boolean enabled) throws IOException, InterruptedException {
        String command = ProcessHandle.current().info().command().orElseThrow(() -> new IOException("无法确定 Agent 可执行文件"));
        String value = '"' + command + '"';
        ProcessBuilder builder = enabled
                ? new ProcessBuilder("reg", "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", "/v", "LitematicGpuAgent", "/t", "REG_SZ", "/d", value, "/f")
                : new ProcessBuilder("reg", "delete", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", "/v", "LitematicGpuAgent", "/f");
        int exit = builder.redirectErrorStream(true).start().waitFor();
        if (exit != 0 && enabled) throw new IOException("写入 Windows 启动项失败");
    }
}
