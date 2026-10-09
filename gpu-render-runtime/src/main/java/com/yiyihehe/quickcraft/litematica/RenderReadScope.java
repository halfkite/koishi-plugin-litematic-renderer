package com.yiyihehe.quickcraft.litematica;

/** Limits render-only NBT shortcuts to the reader thread, never schematic conversion/export. */
public final class RenderReadScope implements AutoCloseable {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Long> SKIPPED = ThreadLocal.withInitial(() -> 0L);
    private boolean closed;

    private RenderReadScope() {
        if (DEPTH.get() == 0) SKIPPED.set(0L);
        DEPTH.set(DEPTH.get() + 1);
    }

    public static RenderReadScope open() { return new RenderReadScope(); }
    public static boolean active() { return DEPTH.get() > 0; }
    public static void containerSkipped() { SKIPPED.set(SKIPPED.get() + 1); }
    public long skippedContainers() { return SKIPPED.get(); }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        int depth = DEPTH.get() - 1;
        if (depth == 0) { DEPTH.remove(); SKIPPED.remove(); } else DEPTH.set(depth);
    }
}
