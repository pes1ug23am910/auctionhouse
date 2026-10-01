package io.auctionhouse.cache;

import com.github.dockerjava.api.model.ExposedPort;
import io.auctionhouse.auction.*;
import io.auctionhouse.auction.domain.AuctionStatus;
import io.auctionhouse.outbox.EventLog;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Separately selected real-server contracts; ordinary CI never substitutes a wire fixture. */
@Testcontainers
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PageKvRealIntegrationTest {
    @Container static final GenericContainer<?> MEMCACHED = new GenericContainer<>(DockerImageName.parse(
            "memcached:1.6.45@sha256:405a445c7c81bca205850426288baa5655c72859ae31d3de3fa823e6482be4ad"))
            .withExposedPorts(11211).withCommand("memcached", "-m", "32", "-U", "0", "-l", "0.0.0.0");
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(
            "postgres@sha256:5a5a84b19854a9ffaa54082c166ff4ec27473a361e496e5ea167f298f2da9722").asCompatibleSubstituteFor("postgres"));
    @TempDir static Path temporary;
    static Process pagekv;
    static int pagekvPort;
    static int generation;
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager transactions;
    enum Backend { MEMCACHED, PAGEKV }
    private static final CacheExpiry TTL = CacheExpiry.after(Duration.ofSeconds(30));
    private static final CacheCodec<String> STRINGS = new CacheCodec<>() {
        public byte[] encode(String value) { return value.getBytes(StandardCharsets.UTF_8); }
        public String decode(byte[] value) { return new String(value, StandardCharsets.UTF_8); }
    };

    @BeforeAll static void setup() throws Exception {
        String executable = System.getenv("PAGEKV_EXECUTABLE");
        assertNotNull(executable, "Set PAGEKV_EXECUTABLE to an actual Linux PageKV server binary");
        assertTrue(Files.isExecutable(Path.of(executable)), "PageKV executable is unavailable");
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        startPageKv();
    }

    static void startPageKv() throws Exception {
        assertTrue(pagekv == null || !pagekv.isAlive(), "Prior PageKV process still alive");
        Path log = temporary.resolve("server-" + ++generation + ".log");
        pagekv = new ProcessBuilder(System.getenv("PAGEKV_EXECUTABLE"), "--directory",
                temporary.resolve("store").toString(), "--bind", "127.0.0.1", "--port", "0")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        await(() -> {
            if (!pagekv.isAlive()) throw new AssertionError("PageKV exited before readiness: " + log);
            try {
                for (String line : Files.readAllLines(log)) {
                    if (line.matches("LISTENING 127\\.0\\.0\\.1:[0-9]+")) {
                        pagekvPort = Integer.parseInt(line.substring(line.lastIndexOf(':') + 1));
                        return true;
                    }
                }
                return false;
            } catch (java.io.IOException error) { throw new RuntimeException(error); }
        }, Duration.ofSeconds(15));
    }

    static void stopPageKv(boolean abrupt) throws Exception {
        if (pagekv == null || !pagekv.isAlive()) return;
        if (abrupt) pagekv.destroyForcibly(); else pagekv.destroy();
        if (!pagekv.waitFor(10, TimeUnit.SECONDS)) {
            pagekv.destroyForcibly();
            assertTrue(pagekv.waitFor(5, TimeUnit.SECONDS), "Owned PageKV process did not stop");
            fail("PageKV did not stop within its deadline");
        }
        if (!abrupt) assertEquals(0, pagekv.exitValue(), "Graceful PageKV shutdown failed");
    }
    @AfterAll static void cleanup() throws Exception { stopPageKv(false); }

    static CacheStore store(Backend backend) {
        if (backend == Backend.PAGEKV) return new PageKvCacheStore("127.0.0.1", pagekvPort, Duration.ofSeconds(2));
        var info = MEMCACHED.getDockerClient().inspectContainerCmd(MEMCACHED.getContainerId()).exec();
        var binding = info.getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(11211));
        assertNotNull(binding, "memcached port not ready");
        return new MemcachedCacheStore(MEMCACHED.getHost(), Integer.parseInt(binding[0].getHostPortSpec()), Duration.ofSeconds(2));
    }
    static String key() { return "real:" + UUID.randomUUID(); }
    static void stop(Backend backend) throws Exception {
        if (backend == Backend.PAGEKV) stopPageKv(true);
        else MEMCACHED.getDockerClient().stopContainerCmd(MEMCACHED.getContainerId()).exec();
    }
    static void start(Backend backend) throws Exception {
        if (backend == Backend.PAGEKV) startPageKv();
        else {
            MEMCACHED.getDockerClient().startContainerCmd(MEMCACHED.getContainerId()).exec();
            await(() -> { try { store(backend).get("ready"); return true; } catch (CacheException error) { return false; } }, Duration.ofSeconds(15));
        }
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void missingOverwriteBinaryEmptyAndRepeatedDeletion(Backend backend) {
        CacheStore cache = store(backend); String key = key();
        assertTrue(cache.get(key).isEmpty());
        byte[] binary = {0, 13, 10, (byte) 255, 42};
        cache.set(key, binary, CacheExpiry.noExpiry());
        assertArrayEquals(binary, cache.get(key).orElseThrow());
        cache.set(key, new byte[0], CacheExpiry.noExpiry());
        assertArrayEquals(new byte[0], cache.get(key).orElseThrow());
        cache.delete(key); cache.delete(key);
        assertTrue(cache.get(key).isEmpty());
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void sharedMaximumKeyAndValueBounds(Backend backend) {
        CacheStore cache = store(backend);
        String key = key() + "x".repeat(209); assertEquals(250, key.length());
        byte[] value = new byte[256 * 1024]; new Random(20261001).nextBytes(value);
        cache.set(key, value, TTL);
        assertArrayEquals(value, cache.get(key).orElseThrow()); cache.delete(key);
        assertThrows(IllegalArgumentException.class, () -> cache.set("large", new byte[value.length + 1], TTL));
        assertThrows(IllegalArgumentException.class, () -> cache.get("x".repeat(251)));
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void positiveTtlExpiresAndExplicitNoExpirySurvives(Backend backend) throws Exception {
        CacheStore cache = store(backend); String expiring = key(), retained = key();
        cache.set(expiring, new byte[]{7}, CacheExpiry.after(Duration.ofSeconds(2)));
        cache.set(retained, new byte[]{8}, CacheExpiry.noExpiry());
        assertArrayEquals(new byte[]{7}, cache.get(expiring).orElseThrow());
        await(() -> cache.get(expiring).isEmpty(), Duration.ofSeconds(8));
        assertArrayEquals(new byte[]{8}, cache.get(retained).orElseThrow()); cache.delete(retained);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void restartHonorsEachBackendsDeclaredPersistenceAndExpiry(Backend backend) throws Exception {
        CacheStore cache = store(backend); String retained = key(), expired = key(), deleted = key();
        byte[] value = new byte[32 * 1024]; new Random(42).nextBytes(value);
        cache.set(retained, value, CacheExpiry.noExpiry());
        cache.set(deleted, value, CacheExpiry.noExpiry()); cache.delete(deleted);
        cache.set(expired, value, CacheExpiry.after(Duration.ofSeconds(2)));
        assertArrayEquals(value, cache.get(retained).orElseThrow());
        stop(backend);
        try { Thread.sleep(2200); } finally { start(backend); }
        CacheStore recovered = store(backend);
        if (backend == Backend.PAGEKV) assertArrayEquals(value, recovered.get(retained).orElseThrow());
        else assertTrue(recovered.get(retained).isEmpty(), "Volatile memcached profile should restart empty");
        assertTrue(recovered.get(expired).isEmpty()); assertTrue(recovered.get(deleted).isEmpty());
        recovered.delete(retained);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void actualOutageFallsBackOnceToTheAuthoritativeLoader(Backend backend) throws Exception {
        var registry = new SimpleMeterRegistry(); var cache = new CacheAsideService(store(backend), registry);
        var loads = new AtomicInteger(); stop(backend);
        try {
            assertEquals("authoritative", cache.getOrLoad(PageKvRealIntegrationTest::key, STRINGS,
                    () -> { loads.incrementAndGet(); return "authoritative"; }, TTL));
            assertEquals(1, loads.get()); assertEquals(1, registry.get("auctionhouse.cache.fallback").counter().count());
        } finally { start(backend); }
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void delayedOldFillCannotReplaceTheCurrentVersion(Backend backend) throws Exception {
        CacheStore store = store(backend); var registry = new SimpleMeterRegistry();
        var cache = new CacheAsideService(store, registry); String prefix = key();
        var version = new AtomicInteger(1); var value = new AtomicReference<>("old");
        var loaded = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var old = executor.submit(() -> cache.getOrLoad(() -> prefix + ":" + version.get(), STRINGS, () -> {
                String snapshot = value.get(); loaded.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new RuntimeException(error); }
                return snapshot;
            }, TTL));
            try {
                assertTrue(loaded.await(10, TimeUnit.SECONDS)); value.set("new"); version.set(2);
                cache.invalidate(prefix + ":1");
                assertEquals("new", cache.getOrLoad(() -> prefix + ":2", STRINGS, value::get, TTL));
            } finally { release.countDown(); }
            assertEquals("old", old.get(10, TimeUnit.SECONDS));
            assertEquals("old", STRINGS.decode(store.get(prefix + ":1").orElseThrow()));
            assertEquals("new", cache.getOrLoad(() -> prefix + ":2", STRINGS, () -> fail("Expected current-version hit"), TTL));
            assertEquals(2, registry.get("auctionhouse.cache.load").counter().count());
            assertEquals(1, registry.get("auctionhouse.cache.hit").counter().count());
        }
    }

    static UUID account() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts(id,issuer,subject,display_name) VALUES (?, 'pagekv-test', ?, 'Test account')", id, id.toString());
        return id;
    }
    static AuctionService service() { return new AuctionService(jdbc, transactions, new EventLog(jdbc), AuctionService.Isolation.ROW_LOCKS, 12); }
    static AuctionBrowse browse(AuctionService service, CacheStore store, SimpleMeterRegistry registry) {
        return new AuctionBrowse(service, new CacheAsideService(store, registry),
                new CacheSettings(store.backendName(), "127.0.0.1", 11211, Duration.ofSeconds(2), 262144, Duration.ofSeconds(30), false),
                TTL, JsonMapper.builder().build());
    }
    static AuctionSnapshot open(AuctionService service, UUID seller) {
        return service.publish(seller, service.create(seller, "Cache integration", "Synthetic fixture", 100, 10, Instant.now().plusSeconds(180)).id());
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void realDatabaseVersionAndAuthorizationRemainAuthoritative(Backend backend) {
        var service = service(); UUID seller = account(), buyer = account(); var auction = open(service, seller);
        var registry = new SimpleMeterRegistry(); var browse = browse(service, store(backend), registry);
        assertEquals(auction.version(), browse.get(buyer, auction.id()).version());
        assertEquals(auction.version(), browse.get(buyer, auction.id()).version());
        assertTrue(service.bid(buyer, auction.id(), "after-cache-fill", 100).accepted());
        assertEquals(auction.version() + 1, browse.get(buyer, auction.id()).version());
        assertEquals(1, registry.get("auctionhouse.cache.hit").counter().count());
        assertEquals(2, registry.get("auctionhouse.cache.load").counter().count());
        var cancelled = open(service, seller); browse.get(buyer, cancelled.id());
        assertEquals(AuctionStatus.CANCELLED, service.cancel(seller, cancelled.id()).status());
        assertEquals("NOT_FOUND", assertThrows(AuctionException.class, () -> browse.get(buyer, cancelled.id())).code());
        assertEquals(AuctionStatus.CANCELLED, browse.get(seller, cancelled.id()).status());
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void realDatabaseMutationBetweenStampAndFillNeverPopulatesTheWrongKey(Backend backend) {
        var service = service(); UUID seller = account(), buyer = account(); var auction = open(service, seller);
        CacheStore actual = store(backend); var lookups = new AtomicInteger();
        CacheStore interleaved = new CacheStore() {
            public Optional<byte[]> get(String key) {
                if (lookups.incrementAndGet() == 1) service.bid(buyer, auction.id(), "real-fill-race", 100);
                return actual.get(key);
            }
            public void set(String key, byte[] value, CacheExpiry expiry) { actual.set(key, value, expiry); }
            public void delete(String key) { actual.delete(key); }
            public String backendName() { return actual.backendName(); }
        };
        var browse = browse(service, interleaved, new SimpleMeterRegistry());
        assertEquals(auction.version() + 1, browse.get(buyer, auction.id()).version());
        assertTrue(actual.get("auction:v1:" + auction.id() + ":" + auction.version()).isEmpty());
        assertEquals(auction.version() + 1, browse.get(buyer, auction.id()).version());
        assertTrue(actual.get("auction:v1:" + auction.id() + ":" + (auction.version() + 1)).isPresent());
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void fixedReadTraceRecordsActualHitsMissesAndDatabaseLoads(Backend backend) {
        var service = service(); UUID seller = account(), buyer = account();
        var auctions = new ArrayList<AuctionSnapshot>(); for (int i = 0; i < 10; i++) auctions.add(open(service, seller));
        var registry = new SimpleMeterRegistry(); var browse = browse(service, store(backend), registry);
        long[] nanos = new long[100];
        for (int i = 0; i < nanos.length; i++) {
            var expected = auctions.get(i % auctions.size()); long start = System.nanoTime();
            assertEquals(expected, browse.get(buyer, expected.id())); nanos[i] = System.nanoTime() - start;
        }
        assertEquals(90, registry.get("auctionhouse.cache.hit").counter().count());
        assertEquals(10, registry.get("auctionhouse.cache.miss").counter().count());
        assertEquals(10, registry.get("auctionhouse.cache.load").counter().count());
        assertEquals(0, registry.get("auctionhouse.cache.fallback").counter().count());
        Arrays.sort(nanos);
        System.out.printf(Locale.ROOT, "CACHE_TRACE backend=%s reads=100 hits=90 misses=10 loads=10 fallbacks=0 p50_ms=%.3f p95_ms=%.3f p99_ms=%.3f%n",
                backend, nanos[49] / 1e6, nanos[94] / 1e6, nanos[98] / 1e6);
    }

    static void await(BooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) fail("Condition not met within " + timeout);
            Thread.sleep(25);
        }
    }
}
