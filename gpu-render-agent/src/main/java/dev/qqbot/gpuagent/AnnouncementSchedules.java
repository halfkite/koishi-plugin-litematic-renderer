package dev.qqbot.gpuagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

final class AnnouncementSchedules {
    private final Path file;
    private final Consumer<String> log;
    private final List<Job> jobs = new ArrayList<>();

    AnnouncementSchedules(Path file, Consumer<String> log) {
        this.file = file;
        this.log = log;
        try {
            if (!Files.isRegularFile(file)) return;
            JsonArray rows = Protocol.GSON.fromJson(Files.readString(file), JsonArray.class);
            if (rows == null) return;
            for (JsonElement element : rows) {
                if (!element.isJsonObject()) continue;
                Job job = Protocol.GSON.fromJson(element, Job.class);
                if (job == null || job.id() == null || job.id().isBlank() || job.content() == null) continue;
                jobs.add("running".equals(job.state())
                        ? job.withResult("interrupted", job.succeeded(), job.failed(), "进程退出时任务未完成；请核对群消息后重新创建")
                        : job);
            }
            save();
        } catch (Exception error) { log.accept("定时公告读取失败：" + error.getMessage()); }
    }

    synchronized List<Job> list() {
        return jobs.stream().sorted(Comparator.comparingLong(Job::runAtMillis).reversed()).toList();
    }

    synchronized Job add(long runAtMillis, String content) throws IOException {
        if (runAtMillis <= System.currentTimeMillis()) throw new IllegalArgumentException("发送时间必须晚于当前时间");
        if (content == null || content.isBlank() || content.length() > 2000)
            throw new IllegalArgumentException("公告内容须为 1 至 2000 字符");
        if (jobs.stream().filter(job -> "pending".equals(job.state())).count() >= 100)
            throw new IllegalArgumentException("待发送公告最多保留 100 条");
        Job job = new Job(UUID.randomUUID().toString(), runAtMillis, content, "pending", 0, 0, "");
        jobs.add(job);
        try { save(); }
        catch (IOException error) { jobs.remove(job); throw error; }
        return job;
    }

    synchronized boolean cancel(String id) throws IOException {
        for (int index = 0; index < jobs.size(); index++) {
            Job job = jobs.get(index);
            if (job.id().equals(id) && "pending".equals(job.state())) {
                jobs.set(index, job.withResult("cancelled", 0, 0, ""));
                try { save(); }
                catch (IOException error) { jobs.set(index, job); throw error; }
                return true;
            }
        }
        return false;
    }

    synchronized Job claimDue(long now) throws IOException {
        for (int index = 0; index < jobs.size(); index++) {
            Job job = jobs.get(index);
            if ("pending".equals(job.state()) && job.runAtMillis() <= now) {
                Job running = job.withResult("running", 0, 0, "");
                jobs.set(index, running);
                try { save(); }
                catch (IOException error) { jobs.set(index, job); throw error; }
                return running;
            }
        }
        return null;
    }

    synchronized void complete(String id, int succeeded, int failed, String error) throws IOException {
        for (int index = 0; index < jobs.size(); index++) {
            Job job = jobs.get(index);
            if (job.id().equals(id) && "running".equals(job.state())) {
                jobs.set(index, job.withResult(failed > 0 || succeeded == 0 ? "partial" : "completed",
                        succeeded, failed, error == null ? "" : error));
                try { save(); }
                catch (IOException saveError) { jobs.set(index, job); throw saveError; }
                return;
            }
        }
    }

    private void save() throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, Protocol.GSON.toJson(jobs));
        try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    record Job(String id, long runAtMillis, String content, String state,
               int succeeded, int failed, String error) {
        Job withResult(String state, int succeeded, int failed, String error) {
            return new Job(id, runAtMillis, content, state, succeeded, failed, error);
        }
    }
}
