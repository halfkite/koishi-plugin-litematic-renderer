package dev.qqbot.gpuagent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 定位当前 Agent 的启动方式，兼容 jpackage 便携包和 java -jar。 */
final class ProcessRelauncher {
    private ProcessRelauncher() {}

    static List<String> currentCommand() throws IOException {
        Path launcher = packagedLauncher();
        if (launcher != null) return List.of(launcher.toAbsolutePath().toString());

        String command = ProcessHandle.current().info().command().orElse("");
        if (command.isBlank()) throw new IOException("无法取得当前启动命令");
        List<String> result = new ArrayList<>();
        result.add(command);
        String[] arguments = ProcessHandle.current().info().arguments().orElse(new String[0]);
        List<String> normalizedArguments = removeRepeatedExecutable(command, arguments);
        if (!normalizedArguments.isEmpty()) {
            result.addAll(normalizedArguments);
        } else {
            result.addAll(fromJavaCommand(System.getProperty("sun.java.command", ""), command));
        }
        while (result.size() > 1 && sameExecutable(result.get(0), result.get(1))) result.remove(1);
        if (result.size() == 1) throw new IOException("无法取得当前 JAR 启动参数（javaw 可能未提供命令行参数）");
        return List.copyOf(result);
    }

    static List<String> removeRepeatedExecutable(String executable, String[] arguments) {
        if (arguments == null || arguments.length == 0) return List.of();
        int start = 0;
        if (sameExecutable(executable, arguments[0])) start = 1;
        List<String> result = new ArrayList<>();
        Collections.addAll(result, arguments);
        if (start == 1) result.remove(0);
        return List.copyOf(result);
    }

    /** Windows 下 javaw 的 ProcessHandle 有时不给 arguments，使用 JVM 保留属性恢复 java -jar。 */
    static List<String> fromJavaCommand(String javaCommand, String executable) {
        if (javaCommand == null || executable == null) return List.of();
        String value = javaCommand.trim();
        if (value.isEmpty()) return List.of();
        if (value.regionMatches(true, 0, "-jar", 0, 4)) value = value.substring(4).trim();
        int jarEnd = value.toLowerCase(java.util.Locale.ROOT).indexOf(".jar");
        if (jarEnd < 0) return List.of();
        jarEnd += 4;
        String jar = stripQuotes(value.substring(0, jarEnd).trim());
        if (jar.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        result.add(executable);
        result.add("-jar");
        result.add(jar);
        result.addAll(splitArguments(value.substring(jarEnd).trim()));
        return List.copyOf(result);
    }

    private static List<String> splitArguments(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(character) && !quoted) {
                if (current.length() > 0) { result.add(current.toString()); current.setLength(0); }
            } else {
                current.append(character);
            }
        }
        if (current.length() > 0) result.add(current.toString());
        return result;
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static boolean sameExecutable(String left, String right) {
        if (left == null || right == null) return false;
        String normalizedLeft = cleanExecutableToken(left);
        String normalizedRight = cleanExecutableToken(right);
        try {
            if (Path.of(normalizedLeft).toAbsolutePath().normalize().equals(Path.of(normalizedRight).toAbsolutePath().normalize())) return true;
        } catch (RuntimeException ignored) { }
        return fileName(normalizedLeft).equalsIgnoreCase(fileName(normalizedRight));
    }

    private static String cleanExecutableToken(String value) {
        return value.replace("\"", "").trim();
    }

    private static String fileName(String value) {
        int separator = Math.max(value.lastIndexOf('\\'), value.lastIndexOf('/'));
        return separator < 0 ? value : value.substring(separator + 1);
    }

    static Path workingDirectory(List<String> command) {
        if (command != null && !command.isEmpty()) {
            Path executable = Path.of(command.get(0)).toAbsolutePath().normalize();
            Path parent = executable.getParent();
            if (parent != null && isLauncher(executable)) return parent;
        }
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    static List<String> command(String executable, String[] arguments) {
        List<String> result = new ArrayList<>();
        result.add(executable);
        if (arguments != null) Collections.addAll(result, arguments);
        return List.copyOf(result);
    }

    private static Path packagedLauncher() {
        Path parentCommand = ProcessHandle.current().parent()
                .flatMap(parent -> parent.info().command())
                .map(Path::of)
                .orElse(null);
        if (parentCommand != null && isLauncher(parentCommand)) return parentCommand;

        try {
            Path javaHome = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize();
            Path appDirectory = javaHome.getParent();
            if (appDirectory == null) return null;
            Path expected = appDirectory.resolve("Litematic GPU Agent.exe");
            if (Files.isRegularFile(expected)) return expected;
            try (var files = Files.list(appDirectory)) {
                return files.filter(ProcessRelauncher::isLauncher).findFirst().orElse(null);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isLauncher(Path path) {
        if (path == null || !Files.isRegularFile(path)) return false;
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        // 不能把 pwsh.exe、explorer.exe 等父进程误认为 jpackage 启动器。
        return name.equals("litematic gpu agent.exe") ||
                (name.startsWith("litematic-gpu-agent") && name.endsWith(".exe"));
    }
}
