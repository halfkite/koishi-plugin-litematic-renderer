package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectionTableRendererTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void splitsProjectionListIntoReadablePages() throws Exception {
        List<String> names = java.util.stream.IntStream.rangeClosed(1, 81)
                .mapToObj(index -> "投影 " + index).toList();

        List<Path> pages = ProjectionTableRenderer.createProjectionList(names, temporaryDirectory);

        assertEquals(2, pages.size());
        assertTrue(javax.imageio.ImageIO.read(pages.getFirst().toFile()).getWidth() >= 760);
        assertTrue(javax.imageio.ImageIO.read(pages.getLast().toFile()).getHeight() > 100);
    }
}
