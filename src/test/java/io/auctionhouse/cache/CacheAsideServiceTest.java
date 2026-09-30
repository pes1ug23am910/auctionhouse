package io.auctionhouse.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CacheAsideServiceTest {
    private static final CacheExpiry EXPIRY = CacheExpiry.after(Duration.ofSeconds(30));
    private static final CacheCodec<String> STRINGS = new CacheCodec<>() {
        public byte[] encode(String value) { return value.getBytes(StandardCharsets.UTF_8); }
        public String decode(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
    };

    private static class MemoryStore implements CacheStore {
        final ConcurrentMap<String, byte[]> values = new ConcurrentHashMap<>();
        public Optional<byte[]> get(String key) { return Optional.ofNullable(values.get(key)).map(bytes -> bytes.clone()); }
        public void set(String key, byte[] value, CacheExpiry expiry) { values.put(key, value.clone()); }
        public void delete(String key) { values.remove(key); }
        public String backendName() { return "fixture"; }
    }

    @Test void everyHitStillReadsAuthoritativeVersionAndRecordsHitMissLoad() {
        var store = new MemoryStore();
        var registry = new SimpleMeterRegistry();
        var cache = new CacheAsideService(store, registry);
        var versionReads = new AtomicInteger();
        var loads = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            assertEquals("current", cache.getOrLoad(() -> {
                versionReads.incrementAndGet(); return "auction:1:v2";
            }, STRINGS, () -> { loads.incrementAndGet(); return "current"; }, EXPIRY));
        }
        assertEquals(2, versionReads.get());
        assertEquals(1, loads.get());
        assertEquals(1, registry.get("auctionhouse.cache.hit").counter().count());
        assertEquals(1, registry.get("auctionhouse.cache.miss").counter().count());
        assertEquals(1, registry.get("auctionhouse.cache.load").counter().count());
    }

    @Test void unreachableCacheFallsBackOnceWithoutAttemptingAnotherWrite() {
        var writes = new AtomicInteger();
        var store = new MemoryStore() {
            public Optional<byte[]> get(String key) { throw new CacheException("offline"); }
            public void set(String key, byte[] value, CacheExpiry expiry) { writes.incrementAndGet(); }
        };
        var registry = new SimpleMeterRegistry();
        var cache = new CacheAsideService(store, registry);
        var loads = new AtomicInteger();
        assertEquals("database", cache.getOrLoad(() -> "v1", STRINGS, () -> {
            loads.incrementAndGet(); return "database";
        }, EXPIRY));
        assertEquals(1, loads.get());
        assertEquals(0, writes.get());
        assertEquals(1, registry.get("auctionhouse.cache.fallback").counter().count());
    }

    @Test void rejectedCacheWriteNeverChangesTheAuthoritativeResponse() {
        var store = new MemoryStore() {
            public void set(String key, byte[] value, CacheExpiry expiry) { throw new CacheException("storage rejected"); }
        };
        var registry = new SimpleMeterRegistry();
        var cache = new CacheAsideService(store, registry);
        assertEquals("database", cache.getOrLoad(() -> "v1", STRINGS, () -> "database", EXPIRY));
        assertEquals(1, registry.get("auctionhouse.cache.fallback").counter().count());
    }

    @Test void corruptValueIsReplacedByAuthoritativeLoad() {
        var store = new MemoryStore();
        store.values.put("v1", new byte[] {0});
        var registry = new SimpleMeterRegistry();
        var cache = new CacheAsideService(store, registry);
        CacheCodec<String> codec = new CacheCodec<>() {
            public byte[] encode(String value) { return value.getBytes(StandardCharsets.UTF_8); }
            public String decode(byte[] bytes) {
                if (bytes[0] == 0) throw new IllegalArgumentException("invalid encoding");
                return new String(bytes, StandardCharsets.UTF_8);
            }
        };
        assertEquals("database", cache.getOrLoad(() -> "v1", codec, () -> "database", EXPIRY));
        assertArrayEquals("database".getBytes(StandardCharsets.UTF_8), store.values.get("v1"));
        assertEquals(1, registry.get("auctionhouse.cache.fallback").counter().count());
    }

    @Test void databaseAndVersionFailuresPropagateWithoutRetryOrStaleFallback() {
        var registry = new SimpleMeterRegistry();
        var cache = new CacheAsideService(new MemoryStore(), registry);
        var attempts = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> cache.getOrLoad(() -> "v1", STRINGS, () -> {
            attempts.incrementAndGet(); throw new IllegalStateException("database unavailable");
        }, EXPIRY));
        assertEquals(1, attempts.get());
        assertThrows(IllegalStateException.class, () -> cache.getOrLoad(() -> {
            throw new IllegalStateException("version query failed");
        }, STRINGS, () -> { attempts.incrementAndGet(); return "must not run"; }, EXPIRY));
        assertEquals(1, attempts.get());
    }

    @Test void nullResultsAreNotNegativeCached() {
        var store = new MemoryStore();
        var cache = new CacheAsideService(store, new SimpleMeterRegistry());
        var loads = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            assertNull(cache.getOrLoad(() -> "v1", STRINGS, () -> { loads.incrementAndGet(); return null; }, EXPIRY));
        }
        assertEquals(2, loads.get());
        assertTrue(store.values.isEmpty());
    }

    @Test void lateOldFillCannotReplaceTheCurrentVersionAfterInvalidation() throws Exception {
        var store = new MemoryStore();
        var registry = new SimpleMeterRegistry();
        var cache = new CacheAsideService(store, registry);
        var version = new AtomicLong(1);
        var database = new AtomicReference<>("old");
        var oldLoaded = new CountDownLatch(1);
        var releaseOld = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var oldRead = executor.submit(() -> cache.getOrLoad(() -> "auction:1:v" + version.get(), STRINGS, () -> {
                String snapshot = database.get();
                oldLoaded.countDown();
                try {
                    if (!releaseOld.await(3, TimeUnit.SECONDS)) throw new AssertionError("old reader not released");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt(); throw new RuntimeException(error);
                }
                return snapshot;
            }, EXPIRY));
            try {
                assertTrue(oldLoaded.await(3, TimeUnit.SECONDS));
                database.set("new");
                version.set(2);
                cache.invalidate("auction:1:v1");
                assertEquals("new", cache.getOrLoad(() -> "auction:1:v" + version.get(), STRINGS, database::get, EXPIRY));
            } finally {
                releaseOld.countDown();
            }
            assertEquals("old", oldRead.get(3, TimeUnit.SECONDS));
            assertEquals("old", STRINGS.decode(store.values.get("auction:1:v1")));
            assertEquals("new", cache.getOrLoad(() -> "auction:1:v" + version.get(), STRINGS, () -> {
                fail("current version should be a hit"); return null;
            }, EXPIRY));
            assertEquals(2, registry.get("auctionhouse.cache.load").counter().count());
            assertEquals(1, registry.get("auctionhouse.cache.invalidation").counter().count());
        }
    }
}
