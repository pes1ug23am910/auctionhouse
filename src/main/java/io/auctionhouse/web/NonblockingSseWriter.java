package io.auctionhouse.web;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** A single serialized nonblocking writer; all queue bounds include the current frame. */
final class NonblockingSseWriter implements WriteListener {
    private static final int CHUNK_BYTES = 1024;
    private static final class Frame {
        final byte[] bytes;
        final boolean sensitive;
        int offset;
        Frame(String text, boolean sensitive) {
            this.bytes = text.getBytes(StandardCharsets.UTF_8);
            this.sensitive = sensitive;
        }
    }
    private final ServletOutputStream output;
    private final BooleanSupplier authorized;
    private final Consumer<String> stopped;
    private final int maxFrames, maxBytes;
    private final ArrayDeque<Frame> queue = new ArrayDeque<>();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private int bufferedBytes;
    private volatile boolean needsFlush, awaitingReady;
    private volatile long lastProgress = System.nanoTime();

    NonblockingSseWriter(ServletOutputStream output, BooleanSupplier authorized,
            Consumer<String> stopped, int maxFrames, int maxBytes) {
        this.output = output; this.authorized = authorized; this.stopped = stopped;
        this.maxFrames = maxFrames; this.maxBytes = maxBytes;
    }

    boolean offer(String text, boolean sensitive) {
        var frame = new Frame(text, sensitive);
        boolean overflow;
        synchronized (queue) {
            if (closed.get()) return false;
            overflow = queue.size() >= maxFrames || frame.bytes.length > maxBytes - bufferedBytes;
            if (!overflow) {
                if (queue.isEmpty() && !needsFlush && !awaitingReady) lastProgress = System.nanoTime();
                queue.addLast(frame);
                bufferedBytes += frame.bytes.length;
            }
        }
        if (overflow) { stop("overflow"); return false; }
        drain();
        return !closed.get();
    }

    @Override public void onWritePossible() { drain(); }
    @Override public void onError(Throwable failure) { stop("io-error"); }

    void drain() {
        if (closed.get() || !draining.compareAndSet(false, true)) return;
        boolean again;
        do {
            try {
                while (!closed.get()) {
                    if (!output.isReady()) { awaitingReady=true; break; }
                    awaitingReady=false;
                    if (needsFlush) {
                        output.flush();
                        needsFlush = false;
                        lastProgress = System.nanoTime();
                        awaitingReady = !output.isReady();
                        if (awaitingReady) break;
                        continue;
                    }
                    Frame frame;
                    synchronized (queue) { frame = queue.peekFirst(); }
                    if (frame == null) break;
                    // This check runs before each frame/chunk, after any backpressure wait.
                    // No queue monitor is held while querying authorization.
                    if (frame.sensitive && !authorized.getAsBoolean()) {
                        stop("authorization"); break;
                    }
                    if (closed.get()) break;
                    if (!output.isReady()) { awaitingReady=true; break; }
                    int count = Math.min(CHUNK_BYTES, frame.bytes.length - frame.offset);
                    output.write(frame.bytes, frame.offset, count);
                    frame.offset += count;
                    lastProgress = System.nanoTime();
                    synchronized (queue) {
                        if (!closed.get()) {
                            bufferedBytes -= count;
                            if (frame.offset == frame.bytes.length) {
                                queue.removeFirst();
                                needsFlush = true;
                            }
                        }
                    }
                }
            } catch (IOException | RuntimeException failure) {
                stop("io-error");
            } finally {
                draining.set(false);
            }
            // An enqueue or container callback can race the release above.
            try {
                again = !closed.get() && pending() && output.isReady() && draining.compareAndSet(false, true);
            } catch (RuntimeException unavailable) {
                stop("io-error");
                again = false;
            }
        } while (again);
    }

    boolean pending() { synchronized (queue) { return !queue.isEmpty() || needsFlush || awaitingReady; } }
    boolean stalled(long now, long timeoutNanos) { return pending() && now - lastProgress >= timeoutNanos; }
    int bufferedBytes() { synchronized (queue) { return bufferedBytes; } }
    int bufferedFrames() { synchronized (queue) { return queue.size(); } }

    void stop(String reason) {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (queue) { queue.clear(); bufferedBytes = 0; needsFlush = false; awaitingReady = false; }
        stopped.accept(reason);
    }
}
