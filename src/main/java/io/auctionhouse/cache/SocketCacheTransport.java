package io.auctionhouse.cache;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;

final class SocketCacheTransport {
    static final int DEFAULT_MAX_VALUE_BYTES = 256 * 1024;
    private static final int MAX_HEADER_BYTES = 512;
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();
    private final InetSocketAddress endpoint;
    private final int timeoutMillis;
    private final int maxValueBytes;

    SocketCacheTransport(String host, int port, Duration timeout, int maxValueBytes) {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(timeout, "timeout");
        if (host.isBlank() || port < 1 || port > 65535) throw new IllegalArgumentException("Invalid cache endpoint");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Cache timeout must be positive and at most 30 seconds");
        }
        if (maxValueBytes < 1 || maxValueBytes > DEFAULT_MAX_VALUE_BYTES) {
            throw new IllegalArgumentException("Cache value limit must be 1..262144 bytes");
        }
        this.endpoint = new InetSocketAddress(host, port);
        this.timeoutMillis = Math.toIntExact(Math.max(1, (timeout.toNanos() + 999_999) / 1_000_000));
        this.maxValueBytes = maxValueBytes;
    }

    private static ScheduledThreadPoolExecutor deadlines() {
        var executor = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon().name("cache-deadlines").factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    interface Operation<T> { T apply(InputStream in, OutputStream out) throws IOException; }

    <T> T execute(Operation<T> operation) {
        try (var socket = new Socket()) {
            ScheduledFuture<?> close = DEADLINES.schedule(() -> {
                try { socket.close(); } catch (IOException ignored) { }
            }, timeoutMillis, TimeUnit.MILLISECONDS);
            try {
                socket.connect(endpoint, timeoutMillis);
                socket.setSoTimeout(timeoutMillis);
                socket.setTcpNoDelay(true);
                return operation.apply(socket.getInputStream(), socket.getOutputStream());
            } finally {
                close.cancel(false);
            }
        } catch (IOException error) {
            throw new CacheException("Cache operation failed or exceeded its deadline", error);
        }
    }

    static String key(String key) {
        Objects.requireNonNull(key, "key");
        if (key.isEmpty() || key.length() > 250) throw new IllegalArgumentException("Cache key must contain 1..250 ASCII bytes");
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c <= 32 || c >= 127) throw new IllegalArgumentException("Cache key must use visible ASCII without whitespace");
        }
        return key;
    }

    byte[] value(byte[] value) {
        Objects.requireNonNull(value, "value");
        if (value.length > maxValueBytes) throw new IllegalArgumentException("Cache value exceeds configured limit");
        return value.clone();
    }

    int length(String token) {
        try {
            if (token.isEmpty() || !token.chars().allMatch(c -> c >= '0' && c <= '9')) throw new NumberFormatException();
            int length = Integer.parseInt(token);
            if (length > maxValueBytes) throw new NumberFormatException();
            return length;
        } catch (NumberFormatException error) {
            throw new CacheException("Cache response declares an invalid or excessive value length");
        }
    }

    static void ascii(OutputStream out, String value) throws IOException {
        out.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    static String line(InputStream in) throws IOException {
        var bytes = new ByteArrayOutputStream();
        while (bytes.size() <= MAX_HEADER_BYTES) {
            int next = in.read();
            if (next == -1) throw new EOFException("Truncated cache response");
            if (next == '\r') {
                if (in.read() != '\n') throw new CacheException("Cache response requires CRLF");
                return bytes.toString(StandardCharsets.US_ASCII);
            }
            if (next == '\n' || next < 32 || next > 126) throw new CacheException("Invalid cache response header");
            bytes.write(next);
        }
        throw new CacheException("Cache response header exceeds limit");
    }

    static byte[] payload(InputStream in, int size) throws IOException {
        byte[] bytes = in.readNBytes(size);
        if (bytes.length != size || in.read() != '\r' || in.read() != '\n') {
            throw new CacheException("Truncated or invalid cache payload");
        }
        return bytes;
    }
}
