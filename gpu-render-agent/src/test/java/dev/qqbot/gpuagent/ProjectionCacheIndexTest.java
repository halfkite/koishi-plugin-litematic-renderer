package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectionCacheIndexTest {
    @Test
    void migratesOldCacheAndKeepsOrdinalsAcrossRenameRebuildAndDeletion() throws Exception {
        Path cache = Files.createTempDirectory("projection-ordinal-");
        try {
            String first = "a".repeat(64);
            String second = "b".repeat(64);
            String third = "c".repeat(64);
            projection(cache, first, "zeta.litematic");
            projection(cache, second, "alpha.litematic");
            List<ProjectionCacheIndex.Entry> migrated = ProjectionCacheIndex.ensure(cache);
            assertEquals(2, migrated.size());
            int firstOrdinal = ordinal(cache, first);
            int secondOrdinal = ordinal(cache, second);
            assertNotEquals(firstOrdinal, secondOrdinal);
            assertTrue(firstOrdinal > 0 && secondOrdinal > 0);

            ProjectionCacheIndex.upsert(cache, first, "renamed.litematic", "zeta.litematic");
            ProjectionCacheIndex.rebuild(cache);
            assertEquals(firstOrdinal, ordinal(cache, first));
            assertEquals(secondOrdinal, ordinal(cache, second));

            Files.delete(cache.resolve(ProjectionCacheIndex.NAME));
            ProjectionCacheIndex.rebuild(cache);
            assertEquals(firstOrdinal, ordinal(cache, first));
            assertEquals(secondOrdinal, ordinal(cache, second));

            Files.delete(cache.resolve(first).resolve("zeta.litematic"));
            ProjectionCacheIndex.rebuild(cache);
            projection(cache, third, "new.litematic");
            ProjectionCacheIndex.upsert(cache, third, "new.litematic", "new.litematic");
            assertTrue(ordinal(cache, third) > Math.max(firstOrdinal, secondOrdinal));
            assertNull(ProjectionSearch.findByOrdinal(cache, firstOrdinal));
        } finally {
            List<Path> paths;
            try (var walk = Files.walk(cache)) {
                paths = walk.sorted(java.util.Comparator.reverseOrder()).toList();
            }
            for (Path path : paths) Files.deleteIfExists(path);
        }
    }

    private static int ordinal(Path cache, String hash) throws Exception {
        return ProjectionCacheIndex.read(cache).stream().filter(entry -> entry.fileHash().equals(hash))
                .findFirst().orElseThrow().ordinal();
    }

    private static void projection(Path cache, String hash, String name) throws Exception {
        Path directory = cache.resolve(hash);
        Files.createDirectories(directory);
        Files.write(directory.resolve(name), new byte[] {10, 0, 0, 0});
        Files.writeString(directory.resolve("about.json5"), "{\"投影文件名\":\"" + name
                + "\",\"存储投影文件名\":\"" + name + "\"}");
    }
}
