package dev.qqbot.gpuagent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 调用 PowerShell 的 WinForms OpenFileDialog 弹出 Windows 现代文件选择对话框。
 * AWT FileDialog / Swing JFileChooser 在 Windows 上都只能显示旧式对话框，
 * 只有 WinForms（IFileDialog）才是资源管理器同款现代样式。
 * Agent 以 GUI 程序运行（无控制台）且重定向了输出，子进程不会闪黑框。
 */
final class NativeFilePicker {
    private NativeFilePicker() {}

    /** 阻塞式选择单个文件；用户取消或出错返回 null。调用线程会被阻塞直到对话框关闭。 */
    static Path chooseFile(String title, String filterLabel, String pattern) {
        String script = "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8;"
            + "Add-Type -AssemblyName System.Windows.Forms | Out-Null;"
            + "$d = New-Object System.Windows.Forms.OpenFileDialog;"
            + "$d.Title = '" + title + "';"
            + "$d.Filter = '" + filterLabel + " (" + pattern + ")|" + pattern + "';"
            + "if ($d.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) { [Console]::Out.Write($d.FileName) }";
        try {
            Process process = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-STA", "-Command", script)
                .redirectErrorStream(false).start();
            String selected;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                selected = reader.readLine();
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroy();
            if (selected == null || selected.isBlank()) return null;
            Path path = Path.of(selected.trim());
            return Files.isRegularFile(path) ? path : null;
        } catch (Exception error) {
            return null;
        }
    }
}
