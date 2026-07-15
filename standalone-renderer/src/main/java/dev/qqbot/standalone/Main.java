package dev.qqbot.standalone;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            Files.createDirectories(options.output);
            System.setProperty("java.awt.headless", "true");
            System.out.println("Reading Litematic: " + options.input);
            Litematic schematic = Litematic.read(options.input, options.maxBlocks);
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
                schematic.blocks().stream().map(Litematic.Block::state).distinct().forEach(models::resolve);
                EntityModelResolver entityModels = new EntityModelResolver(resources, models);
                SoftwareRenderer renderer = new SoftwareRenderer(schematic, models, entityModels);
                SoftwareRenderer.Settings settings = new SoftwareRenderer.Settings(
                    options.resolution, options.supersampling, options.rotation, options.slant, options.fill,
                    options.background, options.transparentBackground
                );

                try (var executor = Executors.newFixedThreadPool(2)) {
                    var normal = executor.submit(() -> {
                        renderer.render(settings, options.rotation, options.output.resolve("isometric.png"));
                        return null;
                    });
                    var reverse = executor.submit(() -> {
                        renderer.render(settings, options.rotation + 180, options.output.resolve("isometric-reverse.png"));
                        return null;
                    });
                    normal.get();
                    reverse.get();
                }
            }
            System.out.println("Standalone render completed: " + options.output);
        } catch (Throwable throwable) {
            throwable.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static final class Options {
        Path input;
        Path output;
        Path minecraftJar;
        final List<Path> resourcePacks = new ArrayList<>();
        int resolution = 1024;
        int supersampling = 2;
        int maxBlocks = 250_000;
        double rotation = 135;
        double slant = 36;
        double fill = 0.78;
        String background = "#000000";
        boolean debugStates;
        boolean transparentBackground;

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
                    case "--max-blocks" -> options.maxBlocks = Math.max(1, Integer.parseInt(value));
                    case "--rotation" -> options.rotation = Double.parseDouble(value);
                    case "--slant" -> options.slant = Math.max(-90, Math.min(90, Double.parseDouble(value)));
                    case "--fill" -> options.fill = Math.max(0.1, Math.min(0.98, Double.parseDouble(value)));
                    case "--background" -> options.background = value;
                    default -> throw new IllegalArgumentException("Unknown option: " + key);
                }
            }
            if (options.input == null || options.output == null || options.minecraftJar == null) {
                throw new IllegalArgumentException("Required: --input FILE --output DIR --minecraft-jar FILE [--resource-pack FILE ...]");
            }
            return options;
        }

        private static String requireValue(String[] args, int index, String key) {
            if (index >= args.length) throw new IllegalArgumentException("Missing value for " + key);
            return args[index];
        }

        private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    }
}
