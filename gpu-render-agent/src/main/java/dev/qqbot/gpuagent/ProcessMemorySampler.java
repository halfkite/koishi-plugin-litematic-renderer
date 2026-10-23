package dev.qqbot.gpuagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;

final class ProcessMemorySampler {
    Map<Long, Long> sample(List<Long> processIds) {
        if (processIds == null || processIds.isEmpty()) return Map.of();
        Set<Long> ids = new LinkedHashSet<>(processIds.stream().filter(id -> id != null && id > 0).toList());
        if (ids.isEmpty()) return Map.of();

        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) return sampleWindows(ids);
        if (os.contains("linux")) return sampleProc(ids);
        if (os.contains("mac") || os.contains("darwin")) return samplePs(ids);
        return Map.of();
    }

    private static Map<Long, Long> sampleProc(Set<Long> ids) {
        Map<Long, Long> result = new LinkedHashMap<>();
        for (long id : ids) {
            try {
                long bytes = parseLinuxStatus(Files.readString(Path.of("/proc", Long.toString(id), "status")));
                if (bytes >= 0) result.put(id, bytes);
            } catch (IOException | RuntimeException ignored) { }
        }
        return result;
    }

    static long parseLinuxStatus(String content) {
        for (String line : content.split("\\R")) {
            if (!line.startsWith("VmRSS:")) continue;
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 2) return -1;
            try {
                long kibibytes = Long.parseLong(fields[1]);
                return Math.multiplyExact(kibibytes, 1024L);
            } catch (NumberFormatException | ArithmeticException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private static Map<Long, Long> sampleWindows(Set<Long> ids) {
        String idList = String.join(",", ids.stream().map(String::valueOf).toList());
        String script = "$ErrorActionPreference='SilentlyContinue'; Get-Process -Id " + idList
                + " | ForEach-Object { [Console]::WriteLine(('{0}:{1}' -f $_.Id,$_.WorkingSet64)) }";
        return runProcess(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script), ids, 1);
    }

    private static Map<Long, Long> samplePs(Set<Long> ids) {
        String idList = String.join(",", ids.stream().map(String::valueOf).toList());
        return runProcess(List.of("ps", "-o", "pid=,rss=", "-p", idList), ids, 1024);
    }

    private static Map<Long, Long> runProcess(List<String> command, Set<Long> expectedIds, long unitMultiplier) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Map.of();
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return parseProcessOutput(output, expectedIds, unitMultiplier);
        } catch (IOException error) {
            return Map.of();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Map.of();
        }
    }

    static Map<Long, Long> parseProcessOutput(String output, Set<Long> expectedIds) {
        return parseProcessOutput(output, expectedIds, 1);
    }

    private static Map<Long, Long> parseProcessOutput(String output, Set<Long> expectedIds, long unitMultiplier) {
        Map<Long, Long> result = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            String[] fields = line.trim().split("[:,\\s]+", 2);
            if (fields.length != 2) continue;
            try {
                long id = Long.parseLong(fields[0]);
                long amount = Math.multiplyExact(Long.parseLong(fields[1]), unitMultiplier);
                if (expectedIds.contains(id) && amount >= 0) result.put(id, amount);
            } catch (NumberFormatException | ArithmeticException ignored) { }
        }
        return Map.copyOf(result);
    }
}
