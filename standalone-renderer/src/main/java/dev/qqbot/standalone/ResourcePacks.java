package dev.qqbot.standalone;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ResourcePacks implements Closeable {
    private interface Source extends Closeable {
        Optional<byte[]> read(String path) throws IOException;
        String description();
    }

    private static final class DirectorySource implements Source {
        private final Path root;
        private DirectorySource(Path root) { this.root = root; }

        @Override public Optional<byte[]> read(String path) throws IOException {
            Path target = root.resolve(path.replace('/', java.io.File.separatorChar)).normalize();
            if (!target.startsWith(root) || !Files.isRegularFile(target)) return Optional.empty();
            return Optional.of(Files.readAllBytes(target));
        }

        @Override public String description() { return root.toString(); }
        @Override public void close() {}
    }

    private static final class ZipSource implements Source {
        private final Path file;
        private final ZipFile zip;
        private final String prefix;

        private ZipSource(Path file) throws IOException {
            this.file = file;
            this.zip = new ZipFile(file.toFile());
            this.prefix = findPrefix(zip);
        }

        @Override public Optional<byte[]> read(String path) throws IOException {
            ZipEntry entry = zip.getEntry(prefix + path);
            if (entry == null || entry.isDirectory()) return Optional.empty();
            try (var input = zip.getInputStream(entry)) {
                return Optional.of(input.readAllBytes());
            }
        }

        @Override public String description() { return file.toString(); }
        @Override public void close() throws IOException { zip.close(); }

        private static String findPrefix(ZipFile zip) {
            if (zip.getEntry("assets/") != null || zip.getEntry("pack.mcmeta") != null ||
                zip.stream().anyMatch(entry -> entry.getName().startsWith("assets/minecraft/"))) return "";
            return zip.stream()
                .map(ZipEntry::getName)
                .filter(name -> name.endsWith("/pack.mcmeta"))
                .map(name -> name.substring(0, name.length() - "pack.mcmeta".length()))
                .findFirst()
                .orElse("");
        }
    }

    private final List<Source> sources = new ArrayList<>();
    private final Map<String, Optional<byte[]>> bytes = new ConcurrentHashMap<>();
    private final Map<String, Optional<JsonElement>> json = new ConcurrentHashMap<>();
    private final Map<String, BufferedImage> images = new ConcurrentHashMap<>();
    private final BufferedImage missingTexture = createMissingTexture();

    ResourcePacks(Path minecraftJar, List<Path> customPacks) throws IOException {
        add(minecraftJar);
        for (Path pack : customPacks) add(pack);
    }

    List<String> descriptions() {
        return sources.stream().map(Source::description).toList();
    }

    Optional<JsonElement> json(String path) {
        String normalized = normalize(path);
        return json.computeIfAbsent(normalized, key -> read(key).flatMap(data -> {
            try {
                return Optional.of(JsonParser.parseString(new String(data, java.nio.charset.StandardCharsets.UTF_8)));
            } catch (RuntimeException exception) {
                System.err.println("Invalid resource JSON " + key + ": " + exception.getMessage());
                return Optional.empty();
            }
        }));
    }

    BufferedImage texture(String namespacedTexture) {
        String normalized = namespacedTexture.contains(":") ? namespacedTexture : "minecraft:" + namespacedTexture;
        return images.computeIfAbsent(normalized, id -> {
            String[] parts = id.split(":", 2);
            String path = "assets/" + parts[0] + "/textures/" + parts[1] + ".png";
            Optional<BufferedImage> loaded = read(path).flatMap(data -> {
                try {
                    return Optional.ofNullable(ImageIO.read(new ByteArrayInputStream(data)));
                } catch (IOException exception) {
                    return Optional.empty();
                }
            });
            if (loaded.isEmpty()) System.err.println("Missing texture: " + id + " (" + path + ")");
            return loaded.orElse(missingTexture);
        });
    }

    boolean hasTexture(String namespacedTexture) {
        String normalized = namespacedTexture.contains(":") ? namespacedTexture : "minecraft:" + namespacedTexture;
        String[] parts = normalized.split(":", 2);
        return read("assets/" + parts[0] + "/textures/" + parts[1] + ".png").isPresent();
    }

    BufferedImage firstTexture(String... namespacedTextures) {
        for (String texture : namespacedTextures) {
            if (hasTexture(texture)) return texture(texture);
        }
        return texture(namespacedTextures[0]);
    }

    BufferedImage missingTexture() { return missingTexture; }

    private void add(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (Files.isDirectory(absolute)) sources.add(new DirectorySource(absolute));
        else if (Files.isRegularFile(absolute)) sources.add(new ZipSource(absolute));
        else throw new IOException("Resource pack does not exist: " + absolute);
    }

    private Optional<byte[]> read(String path) {
        String normalized = normalize(path);
        return bytes.computeIfAbsent(normalized, key -> {
            for (int index = sources.size() - 1; index >= 0; index--) {
                try {
                    Optional<byte[]> result = sources.get(index).read(key);
                    if (result.isPresent()) return result;
                } catch (IOException exception) {
                    System.err.println("Unable to read " + key + " from " + sources.get(index).description() + ": " + exception.getMessage());
                }
            }
            return Optional.empty();
        });
    }

    private static String normalize(String path) {
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        return normalized;
    }

    private static BufferedImage createMissingTexture() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(255, 0, 255)); graphics.fillRect(0, 0, 16, 16);
        graphics.setColor(Color.BLACK); graphics.fillRect(0, 0, 8, 8); graphics.fillRect(8, 8, 8, 8);
        graphics.dispose();
        return image;
    }

    @Override public void close() throws IOException {
        IOException failure = null;
        for (Source source : sources) {
            try { source.close(); } catch (IOException exception) { failure = exception; }
        }
        if (failure != null) throw failure;
    }
}
