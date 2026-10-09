package com.yiyihehe.quickcraft.litematica;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/** Favor cache-write latency over maximum compression for large vertex streams. */
final class MeshCacheCompression extends GZIPOutputStream {
    MeshCacheCompression(OutputStream output) throws IOException {
        super(output, 64 * 1024);
        def.setLevel(Deflater.BEST_SPEED);
    }
}
