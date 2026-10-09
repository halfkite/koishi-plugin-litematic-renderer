package dev.qqbot.gpuagent;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

final class TestDirectoryCleanup {
    private TestDirectoryCleanup() {}

    static void deleteTree(Path root) throws IOException, InterruptedException {
        java.util.List<Path> paths;
        try (var walk = Files.walk(root)) { paths = walk.sorted(Comparator.reverseOrder()).toList(); }
        for (Path path : paths) {
            for (int attempt = 0; ; attempt++) {
                try { Files.deleteIfExists(path); break; }
                catch (DirectoryNotEmptyException error) {
                    // Windows can report pending child deletions after the walk has closed.
                    if (attempt >= 5) throw error;
                    Thread.sleep(30L * (attempt + 1));
                }
            }
        }
    }
}
