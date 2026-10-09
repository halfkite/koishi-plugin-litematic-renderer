package dev.qqbot.standalone;

import java.util.concurrent.CancellationException;

final class RenderCancellation {
    private RenderCancellation() {}

    static void check() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Software render cancelled");
    }
}
