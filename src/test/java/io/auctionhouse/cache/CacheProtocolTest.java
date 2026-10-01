package io.auctionhouse.cache;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class CacheProtocolTest {
    private static final byte[] BINARY = new byte[] {0, '\r', '\n', (byte) 255, 'x'};

    private CacheStore textStore(String backend, Fixture server) {
        return backend.equals("memcached")
                ? new MemcachedCacheStore("127.0.0.1", server.port(), Duration.ofSeconds(2))
                : new PageKvCacheStore("127.0.0.1", server.port(), Duration.ofSeconds(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"memcached", "pagekv"})
    void textProtocolFixturePreservesBinaryPayloadAndTerminator(String backend) throws Exception {
        try (var server = new Fixture(socket -> {
            assertEquals("get binary:v1", readLine(socket.getInputStream()));
            var out = socket.getOutputStream();
            out.write("VALUE binary:v1 0 5\r\n".getBytes(StandardCharsets.US_ASCII));
            out.write(BINARY);
            out.write("\r\nEND\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        })) {
            assertArrayEquals(BINARY, textStore(backend, server).get("binary:v1").orElseThrow());
            server.await();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"memcached", "pagekv"})
    void sharedNoExpiryStorageUsesZeroFlagsAndExactByteCount(String backend) throws Exception {
        try (var server = new Fixture(socket -> {
            var in = socket.getInputStream();
            assertEquals("set binary:v1 0 0 5", readLine(in));
            assertArrayEquals(BINARY, in.readNBytes(5));
            assertEquals('\r', in.read());
            assertEquals('\n', in.read());
            socket.getOutputStream().write("STORED\r\n".getBytes(StandardCharsets.US_ASCII));
        })) {
            textStore(backend, server).set("binary:v1", BINARY, CacheExpiry.noExpiry());
            server.await();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"memcached", "pagekv"})
    void deletionOfMissingKeysIsIdempotent(String backend) throws Exception {
        try (var server = new Fixture(socket -> {
            assertEquals("delete absent:v1", readLine(socket.getInputStream()));
            socket.getOutputStream().write("NOT_FOUND\r\n".getBytes(StandardCharsets.US_ASCII));
        })) {
            textStore(backend, server).delete("absent:v1");
            server.await();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"memcached", "pagekv"})
    void positiveFractionRoundsUpOnTheWire(String backend) throws Exception {
        try (var server = new Fixture(socket -> {
            assertEquals("set k 0 2 0", readLine(socket.getInputStream()));
            assertEquals("", readLine(socket.getInputStream()));
            socket.getOutputStream().write("STORED\r\n".getBytes(StandardCharsets.US_ASCII));
        })) {
            textStore(backend, server)
                    .set("k", new byte[0], CacheExpiry.after(Duration.ofMillis(1500)));
            server.await();
        }
    }

    @Test void keysCannotInjectCommandsAndOversizedValuesAreRejectedLocally() {
        var cache = new MemcachedCacheStore("127.0.0.1", 9, Duration.ofMillis(100), 4);
        for (String key : new String[] {"", "has space", "get\r\nflush_all", "\t", "é", "x".repeat(251)}) {
            assertThrows(IllegalArgumentException.class, () -> cache.get(key));
        }
        assertThrows(IllegalArgumentException.class,
                () -> cache.set("k", new byte[5], CacheExpiry.noExpiry()));
    }

    @Test void oversizedServerLengthCannotCauseUnboundedAllocation() throws Exception {
        try (var server = new Fixture(socket -> {
            readLine(socket.getInputStream());
            socket.getOutputStream().write("VALUE k 0 999999999\r\n".getBytes(StandardCharsets.US_ASCII));
        })) {
            var cache = new MemcachedCacheStore("127.0.0.1", server.port(), Duration.ofSeconds(2));
            assertThrows(CacheException.class, () -> cache.get("k"));
            server.await();
        }
    }

    @Test void unexpectedFlagsAndTruncatedBinaryResponsesAreRejected() throws Exception {
        for (String response : new String[] {"VALUE k 1 1\r\nx\r\nEND\r\n", "VALUE k 0 3\r\nx"}) {
            try (var server = new Fixture(socket -> {
                readLine(socket.getInputStream());
                socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
            })) {
                assertThrows(CacheException.class,
                        () -> new MemcachedCacheStore("127.0.0.1", server.port(), Duration.ofSeconds(2)).get("k"));
                server.await();
            }
        }
    }

    @Test void headerBoundAndDeadlineRejectUnfinishedResponses() throws Exception {
        try (var server = new Fixture(socket -> {
            readLine(socket.getInputStream());
            socket.getOutputStream().write("x".repeat(514).getBytes(StandardCharsets.US_ASCII));
        })) {
            assertThrows(CacheException.class,
                    () -> new MemcachedCacheStore("127.0.0.1", server.port(), Duration.ofSeconds(2)).get("k"));
            server.await();
        }
        try (var server = new Fixture(socket -> {
            readLine(socket.getInputStream());
            assertEquals(-1, socket.getInputStream().read(), "client must close at its deadline");
        })) {
            assertThrows(CacheException.class,
                    () -> new MemcachedCacheStore("127.0.0.1", server.port(), Duration.ofMillis(200)).get("k"));
            server.await();
        }
    }

    @Test void redisProtocolFixtureHandlesNullAndBinaryBulkResponses() throws Exception {
        try (var server = new Fixture(socket -> {
            assertEquals("*2", readLine(socket.getInputStream()));
            assertEquals("$3", readLine(socket.getInputStream()));
            assertEquals("GET", readLine(socket.getInputStream()));
            assertEquals("$1", readLine(socket.getInputStream()));
            assertEquals("k", readLine(socket.getInputStream()));
            var out = socket.getOutputStream();
            out.write("$5\r\n".getBytes(StandardCharsets.US_ASCII));
            out.write(BINARY);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        })) {
            assertArrayEquals(BINARY,
                    new RedisCacheStore("127.0.0.1", server.port(), Duration.ofSeconds(2)).get("k").orElseThrow());
            server.await();
        }
        try (var server = new Fixture(socket -> {
            for (int i = 0; i < 5; i++) readLine(socket.getInputStream());
            socket.getOutputStream().write("$-1\r\n".getBytes(StandardCharsets.US_ASCII));
        })) {
            assertTrue(new RedisCacheStore("127.0.0.1", server.port(), Duration.ofSeconds(2)).get("k").isEmpty());
            server.await();
        }
    }

    private static String readLine(InputStream in) throws IOException {
        var line = new ByteArrayOutputStream();
        int previous = -1;
        for (int i = 0; i < 1024; i++) {
            int next = in.read();
            if (next == -1) throw new EOFException("fixture client disconnected");
            if (previous == '\r' && next == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.US_ASCII);
            }
            line.write(next);
            previous = next;
        }
        throw new IOException("fixture request too long");
    }

    private interface Handler { void run(Socket socket) throws Exception; }

    private static final class Fixture implements AutoCloseable {
        private final ServerSocket listener;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final Future<?> work;
        Fixture(Handler handler) throws IOException {
            listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            work = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(3000);
                    handler.run(socket);
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });
        }
        int port() { return listener.getLocalPort(); }
        void await() throws Exception { work.get(4, TimeUnit.SECONDS); }
        public void close() throws Exception {
            listener.close();
            executor.shutdownNow();
            if (!executor.awaitTermination(4, TimeUnit.SECONDS)) throw new AssertionError("fixture worker did not stop");
        }
    }
}
