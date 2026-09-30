package io.auctionhouse.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.github.dockerjava.api.model.ExposedPort;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class CacheStoreIntegrationTest {
    @Container
    static final GenericContainer<?> MEMCACHED = new GenericContainer<>(DockerImageName.parse(
            "memcached:1.6.45@sha256:405a445c7c81bca205850426288baa5655c72859ae31d3de3fa823e6482be4ad"))
            .withExposedPorts(11211)
            .withCommand("memcached", "-m", "32", "-U", "0", "-l", "0.0.0.0");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(
            "redis:8.10.2@sha256:6f81e8915c60b065a524e6967e0ad1c639ba6efa84d669f823683ea04d9150ee"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--save", "", "--appendonly", "no", "--maxmemory", "32mb", "--maxmemory-policy", "allkeys-lru");

    enum Backend { MEMCACHED, REDIS }

    private GenericContainer<?> container(Backend backend) { return backend == Backend.MEMCACHED ? MEMCACHED : REDIS; }
    private CacheStore store(Backend backend) {
        GenericContainer<?> c = container(backend);
        int containerPort = backend == Backend.MEMCACHED ? 11211 : 6379;
        var info = c.getDockerClient().inspectContainerCmd(c.getContainerId()).exec();
        var bindings = info.getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(containerPort));
        if (bindings == null || bindings.length == 0) throw new CacheException("Cache container port is not ready");
        int mappedPort = Integer.parseInt(bindings[0].getHostPortSpec());
        return backend == Backend.MEMCACHED
                ? new MemcachedCacheStore(c.getHost(), mappedPort, Duration.ofSeconds(2))
                : new RedisCacheStore(c.getHost(), mappedPort, Duration.ofSeconds(2));
    }
    private static String key() { return "contract:" + UUID.randomUUID(); }

    @ParameterizedTest
    @EnumSource(Backend.class)
    void missingOverwriteBinaryEmptyAndRepeatedDeletionShareTheContract(Backend backend) {
        CacheStore cache = store(backend);
        String key = key();
        assertTrue(cache.get(key).isEmpty());
        byte[] binary = new byte[] {0, 13, 10, (byte) 255, 42};
        cache.set(key, binary, CacheExpiry.noExpiry());
        assertArrayEquals(binary, cache.get(key).orElseThrow());
        cache.set(key, new byte[0], CacheExpiry.noExpiry());
        assertArrayEquals(new byte[0], cache.get(key).orElseThrow());
        cache.delete(key);
        cache.delete(key);
        assertTrue(cache.get(key).isEmpty());
    }

    @ParameterizedTest
    @EnumSource(Backend.class)
    void positiveExpiryExpiresWhileExplicitNoExpirySurvivesTheSameInterval(Backend backend) throws Exception {
        CacheStore cache = store(backend);
        String expiring = key(), retained = key();
        byte[] value = {7};
        cache.set(expiring, value, CacheExpiry.after(Duration.ofSeconds(2)));
        cache.set(retained, value, CacheExpiry.noExpiry());
        assertArrayEquals(value, cache.get(expiring).orElseThrow());
        await(() -> cache.get(expiring).isEmpty(), Duration.ofSeconds(8));
        assertArrayEquals(value, cache.get(retained).orElseThrow());
        cache.delete(retained);
    }

    @ParameterizedTest
    @EnumSource(Backend.class)
    void maximumSharedValueAndKeyBoundsAreHonored(Backend backend) {
        CacheStore cache = store(backend);
        byte[] value = new byte[256 * 1024];
        new Random(20261001).nextBytes(value);
        String key = "boundary:" + "x".repeat(241);
        assertEquals(250, key.length());
        cache.set(key, value, CacheExpiry.noExpiry());
        assertArrayEquals(value, cache.get(key).orElseThrow());
        cache.delete(key);
        assertThrows(IllegalArgumentException.class,
                () -> cache.set("oversize", new byte[value.length + 1], CacheExpiry.noExpiry()));
        assertThrows(IllegalArgumentException.class, () -> cache.get("x".repeat(251)));
    }

    @ParameterizedTest
    @EnumSource(Backend.class)
    void cacheProcessRestartDropsValuesInTheDeclaredNonpersistentProfile(Backend backend) throws Exception {
        CacheStore cache = store(backend);
        String key = key();
        cache.set(key, new byte[] {1}, CacheExpiry.noExpiry());
        assertTrue(cache.get(key).isPresent());
        GenericContainer<?> c = container(backend);
        c.getDockerClient().restartContainerCmd(c.getContainerId()).exec();
        await(() -> {
            try { return store(backend).get(key).isEmpty(); }
            catch (CacheException starting) { return false; }
        }, Duration.ofSeconds(15));
    }

    @ParameterizedTest
    @EnumSource(Backend.class)
    void realBackendOutageUsesTheAuthoritativeLoaderAndRecordsFallback(Backend backend) throws Exception {
        CacheStore store = store(backend);
        var registry = new SimpleMeterRegistry();
        var cache = new CacheAsideService(store, registry);
        var calls = new AtomicInteger();
        CacheCodec<String> codec = new CacheCodec<>() {
            public byte[] encode(String value) { return value.getBytes(StandardCharsets.UTF_8); }
            public String decode(byte[] value) { return new String(value, StandardCharsets.UTF_8); }
        };
        GenericContainer<?> c = container(backend);
        c.getDockerClient().stopContainerCmd(c.getContainerId()).exec();
        try {
            assertEquals("database-state", cache.getOrLoad(() -> "auction:v2", codec, () -> {
                calls.incrementAndGet(); return "database-state";
            }, CacheExpiry.after(Duration.ofSeconds(30))));
            assertEquals(1, calls.get());
            assertEquals(1, registry.get("auctionhouse.cache.fallback").counter().count());
        } finally {
            c.getDockerClient().startContainerCmd(c.getContainerId()).exec();
            await(() -> {
                try { store(backend).get("ready"); return true; }
                catch (CacheException starting) { return false; }
            }, Duration.ofSeconds(15));
        }
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) fail("Condition did not become true within " + timeout);
            Thread.sleep(50);
        }
    }
}
