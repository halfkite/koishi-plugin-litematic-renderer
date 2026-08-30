package dev.qqbot.standalone;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            Files.createDirectories(options.output);
            System.setProperty("java.awt.headless", "true");
            System.out.println("Reading Litematic: " + options.input);
            Litematic schematic = Litematic.read(options.input);
            System.out.println("Loaded " + schematic.blocks().size() + " non-air blocks, "
                + schematic.blockEntities().size() + " block entities and " + schematic.entities().size() + " entities");
            if (options.debugStates) {
                schematic.blocks().stream()
                    .collect(java.util.stream.Collectors.groupingBy(Litematic.Block::state, java.util.LinkedHashMap::new, java.util.stream.Collectors.counting()))
                    .forEach((state, count) -> System.out.println(count + " " + state));
                schematic.blocks().stream().filter(block -> block.state().name().contains("chest"))
                    .forEach(block -> System.out.println("CHEST " + block.x() + "," + block.y() + "," + block.z() + " " + block.state().properties()));
            }

            try (ResourcePacks resources = new ResourcePacks(options.minecraftJar, options.resourcePacks)) {
                System.out.println("Resource layers (low to high priority):");
                resources.descriptions().forEach(path -> System.out.println("  " + path));
                ModelResolver models = new ModelResolver(resources);
                EntityModelResolver entityModels = new EntityModelResolver(resources, models);
                SoftwareRenderer renderer = new SoftwareRenderer(schematic, models, entityModels);
                SoftwareRenderer.Settings settings = new SoftwareRenderer.Settings(
                    options.resolution, options.supersampling, options.rotation, options.slant, options.fill,
                    options.background, options.transparentBackground
                );

                if (options.sixFaceOnly) {
                    renderer.renderSixFaces(settings, options.sixFaceResolution, options.sixFaceLayout,
                        options.output.resolve("six-faces.png"));
                } else {
                    renderer.render(settings, options.rotation, options.output.resolve("isometric.png"));
                    renderer.render(settings, options.rotation + 180, options.output.resolve("isometric-reverse.png"));
                }
                writeDiagnostics(options.output.resolve("render-diagnostics.json"), schematic, models);
            }
            System.out.println("Standalone render completed: " + options.output);
        } catch (Throwable throwable) {
            throwable.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void writeDiagnostics(Path output, Litematic schematic, ModelResolver models) throws Exception {
        Map<String, JsonObject> grouped = new LinkedHashMap<>();
        for (ModelResolver.Diagnostic diagnostic : models.diagnostics()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("state", diagnostic.state());
            entry.addProperty("reason", diagnostic.reason());
            long actualCount = schematic.blocks().stream().filter(block -> matchesState(diagnostic.state(), block.state())).count();
            entry.addProperty("count", actualCount > 0 ? actualCount : diagnostic.count());
            JsonArray samples = new JsonArray();
            schematic.blocks().stream().filter(block -> matchesState(diagnostic.state(), block.state())).limit(10).forEach(block -> {
                    JsonObject point = new JsonObject();
                    point.addProperty("x", block.x()); point.addProperty("y", block.y()); point.addProperty("z", block.z());
                    samples.add(point);
                });
            entry.add("samples", samples);
            grouped.put(diagnostic.state() + "\u0000" + diagnostic.reason(), entry);
        }
        JsonObject root = new JsonObject();
        root.addProperty("format", 2);
        root.addProperty("generatedAt", java.time.Instant.now().toString());
        root.addProperty("unsupportedBlockCount", grouped.size());
        JsonArray entries = new JsonArray();
        grouped.values().forEach(entries::add);
        root.add("blocks", entries);
        root.add("errors", new JsonArray());
        Files.writeString(output, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root));
    }

    private static boolean matchesState(String diagnostic, Litematic.BlockState state) {
        StringBuilder value = new StringBuilder(state.name());
        if (!state.properties().isEmpty()) {
            value.append('[');
            state.properties().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                if (value.charAt(value.length() - 1) != '[') value.append(',');
                value.append(entry.getKey()).append('=').append(entry.getValue());
            });
            value.append(']');
        }
        return diagnostic.equals(value.toString());
    }

    private static final class Options {
        Path input;
        Path output;
        Path minecraftJar;
        final List<Path> resourcePacks = new ArrayList<>();
        int resolution = 1024;
        int supersampling = 2;
        double rotation = 135;
        double slant = 36;
        double fill = 0.78;
        int sixFaceResolution = 1024;
        String sixFaceLayout = "horizontal";
        String background = "#000000";
        boolean debugStates;
        boolean transparentBackground;
        boolean sixFaceOnly;

        static Options parse(String[] args) {
            Options options = new Options();
            for (int index = 0; index < args.length; index++) {
                String key = args[index];
                if (key.equals("--debug-states")) {
                    options.debugStates = true;
                    continue;
                }
                if (key.equals("--transparent-background")) {
                    options.transparentBackground = true;
                    continue;
                }
                if (key.equals("--six-face-only")) {
                    options.sixFaceOnly = true;
                    continue;
                }
                if (key.equals("--resource-pack")) {
                    options.resourcePacks.add(Path.of(requireValue(args, ++index, key)).toAbsolutePath().normalize());
                    continue;
                }
                String value = requireValue(args, ++index, key);
                switch (key) {
                    case "--input" -> options.input = Path.of(value).toAbsolutePath().normalize();
                    case "--output" -> options.output = Path.of(value).toAbsolutePath().normalize();
                    case "--minecraft-jar" -> options.minecraftJar = Path.of(value).toAbsolutePath().normalize();
                    case "--resolution" -> options.resolution = clamp(Integer.parseInt(value), 256, 4096);
                    case "--supersampling" -> options.supersampling = clamp(Integer.parseInt(value), 1, 4);
                    case "--rotation" -> options.rotation = Double.parseDouble(value);
                    case "--slant" -> options.slant = Math.max(-90, Math.min(90, Double.parseDouble(value)));
                    case "--fill" -> options.fill = Math.max(0.1, Math.min(0.98, Double.parseDouble(value)));
                    case "--six-face-resolution" -> options.sixFaceResolution = clamp(Integer.parseInt(value), 128, 4096);
                    case "--six-face-layout" -> options.sixFaceLayout = requireLayout(value);
                    case "--background" -> options.background = value;
                    default -> throw new IllegalArgumentException("Unknown option: " + key);
                }
            }
            if (options.input == null || options.output == null || options.minecraftJar == null) {
                throw new IllegalArgumentException("Required: --input FILE --output DIR --minecraft-jar FILE [--resource-pack FILE ...] [--six-face-only]");
            }
            return options;
        }

        private static String requireValue(String[] args, int index, String key) {
            if (index >= args.length) throw new IllegalArgumentException("Missing value for " + key);
            return args[index];
        }

        private static String requireLayout(String value) {
            if (value.equals("horizontal") || value.equals("vertical")) return value;
            throw new IllegalArgumentException("--six-face-layout must be horizontal or vertical");
        }

        private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    }
}
