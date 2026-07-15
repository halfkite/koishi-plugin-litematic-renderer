package dev.qqbot.standalone;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

final class NbtReader {
    private NbtReader() {}

    static Map<String, Object> read(Path path) throws IOException {
        try (InputStream file = Files.newInputStream(path);
             BufferedInputStream buffered = new BufferedInputStream(file)) {
            buffered.mark(2);
            int first = buffered.read();
            int second = buffered.read();
            buffered.reset();
            InputStream decoded = first == 0x1f && second == 0x8b ? new GZIPInputStream(buffered) : buffered;
            try (DataInputStream input = new DataInputStream(decoded)) {
                int rootType = input.readUnsignedByte();
                if (rootType != 10) throw new IOException("NBT root is not a compound tag");
                readString(input);
                return compound(input);
            }
        }
    }

    private static Object value(DataInputStream input, int type) throws IOException {
        return switch (type) {
            case 0 -> null;
            case 1 -> input.readByte();
            case 2 -> input.readShort();
            case 3 -> input.readInt();
            case 4 -> input.readLong();
            case 5 -> input.readFloat();
            case 6 -> input.readDouble();
            case 7 -> {
                byte[] values = new byte[input.readInt()];
                input.readFully(values);
                yield values;
            }
            case 8 -> readString(input);
            case 9 -> list(input);
            case 10 -> compound(input);
            case 11 -> {
                int[] values = new int[input.readInt()];
                for (int index = 0; index < values.length; index++) values[index] = input.readInt();
                yield values;
            }
            case 12 -> {
                long[] values = new long[input.readInt()];
                for (int index = 0; index < values.length; index++) values[index] = input.readLong();
                yield values;
            }
            default -> throw new IOException("Unsupported NBT tag type: " + type);
        };
    }

    private static Map<String, Object> compound(DataInputStream input) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        while (true) {
            int type = input.readUnsignedByte();
            if (type == 0) return result;
            result.put(readString(input), value(input, type));
        }
    }

    private static List<Object> list(DataInputStream input) throws IOException {
        int type = input.readUnsignedByte();
        int size = input.readInt();
        List<Object> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) result.add(value(input, type));
        return result;
    }

    private static String readString(DataInputStream input) throws IOException {
        byte[] bytes = new byte[input.readUnsignedShort()];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
