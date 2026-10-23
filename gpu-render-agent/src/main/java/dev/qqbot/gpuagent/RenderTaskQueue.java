package dev.qqbot.gpuagent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Queue dispatch and drain transitions share one monitor so a task cannot slip into flight during a drain. */
final class RenderTaskQueue<T> {
    enum OfferResult { ACCEPTED, DRAINING, CLOSED }

    private final ArrayDeque<T> queued = new ArrayDeque<>();
    private int inFlight;
    private boolean draining;
    private boolean closed;
    private Runnable drainCompleteListener;

    synchronized OfferResult offer(T task) {
        if (closed) return OfferResult.CLOSED;
        if (draining) return OfferResult.DRAINING;
        queued.addLast(task);
        notifyAll();
        return OfferResult.ACCEPTED;
    }

    synchronized T take() throws InterruptedException {
        while (!closed && (draining || queued.isEmpty())) wait();
        if (closed) return null;
        inFlight++;
        return queued.removeFirst();
    }

    synchronized void startDraining() {
        draining = true;
        Runnable callback = readyCallback();
        notifyAll();
        dispatch(callback);
    }

    synchronized void setDrainCompleteListener(Runnable listener) {
        drainCompleteListener = listener;
        dispatch(readyCallback());
    }

    synchronized void completeTask() {
        if (inFlight <= 0) throw new IllegalStateException("No render task is in flight");
        inFlight--;
        dispatch(readyCallback());
    }

    synchronized void resume() {
        draining = false;
        notifyAll();
    }

    synchronized List<T> drainQueued() {
        List<T> pending = new ArrayList<>(queued);
        queued.clear();
        return pending;
    }

    synchronized List<T> close() {
        closed = true;
        drainCompleteListener = null;
        List<T> pending = new ArrayList<>(queued);
        queued.clear();
        notifyAll();
        return pending;
    }

    synchronized int size() { return queued.size() + inFlight; }
    synchronized int inFlightCount() { return inFlight; }
    synchronized boolean isDraining() { return draining; }

    private Runnable readyCallback() {
        if (!draining || inFlight != 0 || drainCompleteListener == null) return null;
        Runnable callback = drainCompleteListener;
        drainCompleteListener = null;
        return callback;
    }

    private static void dispatch(Runnable callback) {
        if (callback != null) Thread.startVirtualThread(callback);
    }
}
