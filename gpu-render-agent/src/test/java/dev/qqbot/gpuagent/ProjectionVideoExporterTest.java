package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ProjectionVideoExporterTest {
    @TempDir Path temporaryDirectory;

    @Test
    void validatesEvenDimensionsAndCapsTemporaryFrameSpace() {
        assertDoesNotThrow(() -> ProjectionVideoExporter.validateSettings(1280, 720, 8, 24));
        assertThrows(IllegalArgumentException.class,
                () -> ProjectionVideoExporter.validateSettings(1279, 720, 8, 24));
        assertThrows(IllegalArgumentException.class,
                () -> ProjectionVideoExporter.validateSettings(1920, 1080, 60, 60));
    }

    @Test
    void createsAFullOrbitWithoutDuplicatingTheEndpoint() {
        var views = ProjectionVideoExporter.orbitViews(1280, 720, 192);
        assertEquals(192, views.size());
        assertEquals("frame-00000", views.getFirst().id());
        assertEquals(135.0, views.getFirst().yaw());
        assertEquals(36.0, views.getFirst().pitch());
        assertFalse(views.getFirst().autoFill());
        assertEquals(1.0, views.getFirst().zoom());
        assertTrue(views.getLast().yaw() < 495.0);
        assertEquals(135.0 + 360.0 * 191 / 192, views.getLast().yaw());
    }

    @Test
    void ffmpegEncodesTheTemporaryPngSequenceToMp4() throws Exception {
        Process probe;
        try {
            probe = new ProcessBuilder("ffmpeg", "-hide_banner", "-encoders").redirectErrorStream(true).start();
        } catch (Exception unavailable) {
            assumeTrue(false, "FFmpeg is not installed on PATH");
            return;
        }
        String encoderList = new String(probe.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, probe.waitFor());
        assumeTrue(encoderList.contains("libx264"), "FFmpeg does not include libx264");

        Path frames = Files.createDirectory(temporaryDirectory.resolve("frames"));
        for (int frame = 0; frame < 2; frame++) {
            BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            graphics.setColor(frame == 0 ? Color.RED : Color.BLUE);
            graphics.fillRect(0, 0, 64, 64);
            graphics.dispose();
            assertTrue(ImageIO.write(image, "png", frames.resolve(String.format(java.util.Locale.ROOT,
                    "frame-%05d.png", frame)).toFile()));
        }

        Path output = temporaryDirectory.resolve("projection.mp4");
        AtomicReference<Process> active = new AtomicReference<>();
        ProjectionVideoExporter.encode("ffmpeg", frames, 2, 2, output, () -> false, active::set, ignored -> {});
        assertTrue(Files.size(output) > 0);
        assertNull(active.get());
        assertFalse(Files.exists(output.resolveSibling(output.getFileName() + ".partial.mp4")));
    }

    @Test
    void sanitizesOutputNamesForWindowsAndLinux() {
        assertEquals("a_b_c.mp4", ProjectionVideoExporter.safeOutputFileName("a:b/c.litematic"));
        assertEquals("_CON.mp4", ProjectionVideoExporter.safeOutputFileName("CON.litematic"));
    }
}
