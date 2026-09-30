package io.auctionhouse.auction;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import io.auctionhouse.outbox.EventLog;
import io.auctionhouse.cache.*;
import io.auctionhouse.auction.domain.AuctionStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Testcontainers
class AuctionServiceIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("postgres@sha256:5a5a84b19854a9ffaa54082c166ff4ec27473a361e496e5ea167f298f2da9722").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager transactions;
    UUID seller;
    UUID buyer;

    @BeforeAll static void database() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
    }

    @BeforeEach void accounts() {
        seller = account();
        buyer = account();
    }

    UUID account() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts(id,issuer,subject,display_name) VALUES (?, 'test', ?, 'Test account')", id, id.toString());
        return id;
    }

    AuctionService service(AuctionService.Isolation strategy) {
        return new AuctionService(jdbc, transactions, new EventLog(jdbc), strategy, 12);
    }

    AuctionSnapshot open(AuctionService service) {
        var draft = service.create(seller, "Test auction", "Seeded integration fixture", 100, 10, Instant.now().plusSeconds(180));
        return service.publish(seller, draft.id());
    }

    @Test void ownerCancellationBetweenStampAndFillCannotLeakThroughSharedCache() {
        var service = service(AuctionService.Isolation.ROW_LOCKS);
        var auction = open(service);
        var values = new HashMap<String, byte[]>();
        var lookups = new AtomicInteger();
        var browseRef = new AtomicReference<AuctionBrowse>();
        CacheStore store = new CacheStore() {
            public Optional<byte[]> get(String key) {
                int lookup = lookups.incrementAndGet();
                if (lookup == 1) {
                    // Both readers already observed the public version; the owner then sees cancellation.
                    assertEquals(AuctionStatus.CANCELLED, browseRef.get().get(seller, auction.id()).status());
                } else if (lookup == 2) {
                    service.cancel(seller, auction.id());
                }
                return Optional.ofNullable(values.get(key));
            }
            public void set(String key, byte[] value, CacheExpiry expiry) { values.put(key, value); }
            public void delete(String key) { values.remove(key); }
            public String backendName() { return "interleaving-fixture"; }
        };
        var browse = browse(service, store);
        browseRef.set(browse);
        assertEquals("NOT_FOUND", assertThrows(AuctionException.class,
                () -> browse.get(buyer, auction.id())).code());
        assertEquals(2, lookups.get());
        assertTrue(values.isEmpty(), "private cancellation must never be written under the public key");
    }

    @Test void newerLoaderVersionIsReturnedButNeverStoredUnderEarlierStamp() {
        var service = service(AuctionService.Isolation.ROW_LOCKS);
        var auction = open(service);
        var values = new HashMap<String, byte[]>();
        var lookups = new AtomicInteger();
        CacheStore store = new CacheStore() {
            public Optional<byte[]> get(String key) {
                if (lookups.incrementAndGet() == 1) service.bid(buyer, auction.id(), "cache-race", 100);
                return Optional.ofNullable(values.get(key));
            }
            public void set(String key, byte[] value, CacheExpiry expiry) { values.put(key, value); }
            public void delete(String key) { values.remove(key); }
            public String backendName() { return "interleaving-fixture"; }
        };
        var browse = browse(service, store);
        assertEquals(auction.version() + 1, browse.get(buyer, auction.id()).version());
        assertTrue(values.isEmpty(), "a newer payload must not fill an older version key");
        assertEquals(auction.version() + 1, browse.get(buyer, auction.id()).version());
        assertEquals(Set.of("auction:v1:" + auction.id() + ":" + (auction.version() + 1)), values.keySet());
    }

    private AuctionBrowse browse(AuctionService service, CacheStore store) {
        return new AuctionBrowse(service, new CacheAsideService(store, new SimpleMeterRegistry()),
                new CacheSettings("memcached", "127.0.0.1", 11211, Duration.ofMillis(500),
                        262144, Duration.ofSeconds(30), false),
                CacheExpiry.after(Duration.ofSeconds(30)), JsonMapper.builder().build());
    }

    @ParameterizedTest @EnumSource(AuctionService.Isolation.class)
    void expiredOutcomeCannotBeReusedForAnotherEffect(AuctionService.Isolation strategy) {
        var first=service(strategy);
        var second=service(strategy);
        var auction=open(first);
        assertTrue(first.bid(buyer,auction.id(),"expired-key",150).accepted());
        jdbc.update("UPDATE bid_intents SET replay_until=clock_timestamp()-interval '1 second' WHERE auction_id=?",auction.id());
        var expired=assertThrows(AuctionException.class,() -> second.intent(buyer,auction.id(),"expired-key"));
        assertEquals("INTENT_EXPIRED",expired.code());
        assertEquals(410,expired.status());
        assertEquals("INTENT_EXPIRED",assertThrows(AuctionException.class,
                () -> second.bid(buyer,auction.id(),"expired-key",150)).code());
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(AuctionException.class,
                () -> second.bid(buyer,auction.id(),"expired-key",200)).code());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM bids WHERE auction_id=?",Integer.class,auction.id()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=? AND event_type='bid.accepted'",Integer.class,auction.id()));
        assertTrue(second.intent(account(),auction.id(),"expired-key").isEmpty());
    }

    @ParameterizedTest @EnumSource(AuctionService.Isolation.class)
    void committedResponseCanBeRecoveredOnAnotherInstanceAfterClosure(AuctionService.Isolation strategy) {
        var first = service(strategy);
        var second = service(strategy);
        var auction = open(first);
        var lost = first.bid(buyer, auction.id(), "lost-response", 120);
        assertTrue(lost.accepted());
        jdbc.update("UPDATE auctions SET ends_at=clock_timestamp()-interval '1 second' WHERE id=?", auction.id());
        first.close(seller, auction.id());
        assertEquals(lost, second.bid(buyer, auction.id(), "lost-response", 120));
        assertEquals(lost, second.intent(buyer, auction.id(), "lost-response").orElseThrow());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM bids WHERE auction_id=?", Integer.class, auction.id()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=? AND event_type='bid.accepted'", Integer.class, auction.id()));
        assertEquals(buyer, first.get(seller, auction.id()).winnerId());
        assertFalse(second.bid(account(), auction.id(), "too-late", 1000).accepted());
    }

    @ParameterizedTest @EnumSource(AuctionService.Isolation.class)
    void sameKeyConcurrentAcrossInstancesHasOneEffectAndConflictingPayloadFails(AuctionService.Isolation strategy) throws Exception {
        var first = service(strategy);
        var second = service(strategy);
        var auction = open(first);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            List<Future<BidOutcome>> replies = IntStream.range(0, 24).mapToObj(i -> pool.submit(() -> {
                start.await();
                return (i % 2 == 0 ? first : second).bid(buyer, auction.id(), "same-key", 150);
            })).toList();
            start.countDown();
            var expected = replies.getFirst().get(30, TimeUnit.SECONDS);
            for (var reply : replies) assertEquals(expected, reply.get(30, TimeUnit.SECONDS));
        }
        var conflict = assertThrows(AuctionException.class, () -> second.bid(buyer, auction.id(), "same-key", 200));
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.code());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM bids WHERE auction_id=?", Integer.class, auction.id()));
        assertTrue(second.intent(account(), auction.id(), "same-key").isEmpty());
    }

    @ParameterizedTest @EnumSource(AuctionService.Isolation.class)
    void concurrentRejectedRetriesStillHaveOneDurableOutcome(AuctionService.Isolation strategy) throws Exception {
        var first = service(strategy);
        var second = service(strategy);
        var auction = open(first);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var replies = IntStream.range(0, 16).mapToObj(i -> pool.submit(() -> {
                start.await();
                return (i % 2 == 0 ? first : second).bid(buyer, auction.id(), "same-rejection", 1);
            })).toList();
            start.countDown();
            var expected = replies.getFirst().get(30, TimeUnit.SECONDS);
            assertFalse(expected.accepted());
            for (var reply : replies) assertEquals(expected, reply.get(30, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM bid_intents WHERE auction_id=?", Integer.class, auction.id()));
    }

    @Test void rejectedOutcomeIsDurableAndOwnerCannotBid() {
        var service = service(AuctionService.Isolation.ROW_LOCKS);
        var auction = open(service);
        var rejected = service.bid(buyer, auction.id(), "too-low", 99);
        assertEquals("BID_TOO_LOW", rejected.rejection());
        service.bid(account(), auction.id(), "higher", 200);
        assertEquals(rejected, service.bid(buyer, auction.id(), "too-low", 99));
        assertEquals("OWNER_CANNOT_BID", service.bid(seller, auction.id(), "own", 300).rejection());
    }

    @Test void lockWaitCannotUseTransactionStartTimeToAcceptAnExpiredBid() throws Exception {
        var service = service(AuctionService.Isolation.ROW_LOCKS);
        var auction = open(service);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var locker = pool.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> {
                jdbc.queryForObject("SELECT id FROM auctions WHERE id=? FOR UPDATE", UUID.class, auction.id());
                jdbc.update("UPDATE auctions SET ends_at=clock_timestamp()+interval '400 milliseconds' WHERE id=?", auction.id());
                held.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                return null;
            }));
            assertTrue(held.await(5, TimeUnit.SECONDS));
            var bid = pool.submit(() -> service.bid(buyer, auction.id(), "waited", 120));
            Thread.sleep(700);
            release.countDown();
            locker.get(5, TimeUnit.SECONDS);
            assertEquals("AUCTION_ENDED", bid.get(5, TimeUnit.SECONDS).rejection());
        }
    }

    @Test void outboxFailureRollsBackBidOutcomeAndAuctionTogether() {
        var normal = service(AuctionService.Isolation.ROW_LOCKS);
        var auction = open(normal);
        EventLog broken = new EventLog(jdbc) {
            @Override public void append(AuctionSnapshot snapshot, String type, Instant occurredAt) { throw new IllegalStateException("simulated write failure"); }
        };
        var service = new AuctionService(jdbc, transactions, broken, AuctionService.Isolation.ROW_LOCKS, 2);
        assertThrows(IllegalStateException.class, () -> service.bid(buyer, auction.id(), "rollback", 120));
        assertTrue(normal.intent(buyer, auction.id(), "rollback").isEmpty());
        assertNull(normal.get(buyer, auction.id()).highestBidAmountMinor());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM bids WHERE auction_id=?", Integer.class, auction.id()));
        assertTrue(normal.bid(buyer, auction.id(), "rollback", 120).accepted());
    }

    @ParameterizedTest(name="{0}, scheduled closure={1}, bid owns lock first={2}")
    @CsvSource({
        "ROW_LOCKS,false,true", "ROW_LOCKS,true,true",
        "SERIALIZABLE,false,true", "SERIALIZABLE,true,true",
        "ROW_LOCKS,false,false", "ROW_LOCKS,true,false",
        "SERIALIZABLE,false,false", "SERIALIZABLE,true,false"
    })
    void closureRacingABidPreservesTheCommittedWinnerAndEventOrder(
            AuctionService.Isolation strategy, boolean scheduled, boolean bidFirst) throws Exception {
        var normal = service(strategy);
        var auction = open(normal);
        UUID challenger = account();
        BidOutcome previous = bidFirst ? null : normal.bid(buyer, auction.id(), "earlier-winner", 100);
        // The deadline is fixed before either racing operation. Only the bid-first case waits for real DB time.
        jdbc.update("UPDATE auctions SET ends_at=clock_timestamp()+?::interval WHERE id=?",
                bidFirst ? "2 seconds" : "-1 second", auction.id());
        var deadline = normal.get(seller, auction.id()).endsAt();
        var written = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holderPid = new AtomicInteger();
        EventLog heldEvent = new EventLog(jdbc) {
            @Override public void append(AuctionSnapshot snapshot, String type, Instant occurredAt) {
                super.append(snapshot, type, occurredAt);
                if (snapshot.id().equals(auction.id())
                        && type.equals(bidFirst ? "bid.accepted" : "auction.closed")) {
                    holderPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    written.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture did not release transaction");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Fixture interrupted", interrupted);
                    }
                }
            }
        };
        var held = new AuctionService(jdbc, transactions, heldEvent, strategy, 12);
        BidOutcome raced;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                if (bidFirst) {
                    var bid = executor.submit(() -> held.bid(challenger, auction.id(), "racing-bid", 150));
                    assertTrue(written.await(5, TimeUnit.SECONDS), "accepted bid reached its uncommitted outbox boundary");
                    awaitCondition(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                            "SELECT clock_timestamp()>=ends_at FROM auctions WHERE id=?", Boolean.class, auction.id())), 5000);
                    var closing = executor.submit(() -> closeWith(normal, scheduled, auction.id()));
                    awaitBlockedBy(holderPid.get());
                    assertFalse(closing.isDone(), "closure must wait for the accepted bid's transaction");
                    release.countDown();
                    raced = bid.get(10, TimeUnit.SECONDS);
                    closing.get(10, TimeUnit.SECONDS);
                    assertTrue(raced.accepted());
                    assertTrue(raced.decidedAt().isBefore(deadline));
                } else {
                    var closing = executor.submit(() -> closeWith(held, scheduled, auction.id()));
                    assertTrue(written.await(5, TimeUnit.SECONDS), "closure reached its uncommitted outbox boundary");
                    var bid = executor.submit(() -> normal.bid(challenger, auction.id(), "racing-bid", 150));
                    awaitBlockedBy(holderPid.get());
                    assertFalse(bid.isDone(), "a new bid must wait for the closing transaction");
                    release.countDown();
                    closing.get(10, TimeUnit.SECONDS);
                    raced = bid.get(10, TimeUnit.SECONDS);
                    assertFalse(raced.accepted());
                    assertEquals("AUCTION_NOT_OPEN", raced.rejection());
                    assertNull(raced.bidId());
                }
            } finally {
                release.countDown();
            }
        }
        var winner = bidFirst ? raced : previous;
        var closed = normal.get(seller, auction.id());
        assertEquals(AuctionStatus.CLOSED, closed.status());
        assertEquals(deadline, closed.endsAt(), "neither contender may extend the fixed deadline");
        assertEquals(winner.actorId(), closed.winnerId());
        assertEquals(winner.amountMinor(), closed.highestBidAmountMinor());
        assertEquals(winner.auctionVersion() + 1, closed.version());
        assertEquals(raced, normal.intent(challenger, auction.id(), "racing-bid").orElseThrow());
        assertEquals(raced, normal.bid(challenger, auction.id(), "racing-bid", 150));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM bids WHERE auction_id=?", Integer.class, auction.id()));
        assertEquals(winner.bidId(), jdbc.queryForObject("SELECT id FROM bids WHERE auction_id=?", UUID.class, auction.id()));
        assertEquals(bidFirst ? 1 : 2, jdbc.queryForObject("SELECT count(*) FROM bid_intents WHERE auction_id=?", Integer.class, auction.id()));
        assertEquals(List.of("auction.created", "auction.published", "bid.accepted", "auction.closed"),
                jdbc.queryForList("SELECT event_type FROM outbox_events WHERE aggregate_id=? ORDER BY aggregate_version", String.class, auction.id()));
        assertEquals(List.of(1L, 2L, 3L, 4L), jdbc.queryForList(
                "SELECT aggregate_version FROM outbox_events WHERE aggregate_id=? ORDER BY aggregate_version", Long.class, auction.id()));
        var finalEvent = JsonMapper.builder().build().readTree(jdbc.queryForObject(
                "SELECT payload::text FROM outbox_events WHERE aggregate_id=? AND event_type='auction.closed'", String.class, auction.id()));
        assertEquals(winner.actorId().toString(), finalEvent.get("winnerId").asString());
        assertEquals(winner.amountMinor(), finalEvent.get("highestBidAmountMinor").asLong());
        assertEquals(closed.version(), finalEvent.get("version").asLong());
    }

    private void closeWith(AuctionService service, boolean scheduled, UUID auction) {
        if (scheduled) assertTrue(service.closeDue() >= 1);
        else assertEquals(AuctionStatus.CLOSED, service.close(seller, auction).status());
    }

    private void awaitBlockedBy(int backendPid) throws InterruptedException {
        awaitCondition(() -> jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE state='active' AND ?=ANY(pg_blocking_pids(pid))",
                Integer.class, backendPid) > 0, 2500);
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "required database interleaving was not observed");
    }

    @ParameterizedTest @EnumSource(AuctionService.Isolation.class)
    void concurrentHistoryMatchesIndependentMonotonicOracle(AuctionService.Isolation strategy) throws Exception {
        var service = service(strategy);
        var second = service(strategy);
        var auction = open(service);
        var actors = IntStream.range(0, 40).mapToObj(i -> account()).toList();
        var amounts = new ArrayList<>(IntStream.range(0, 40).mapToObj(i -> 100L + 10L * i).toList());
        Collections.shuffle(amounts, new Random(6389));
        List<BidOutcome> outcomes = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var replies = IntStream.range(0, 40).mapToObj(i -> pool.submit(() -> {
                start.await();
                return (i % 2 == 0 ? service : second).bid(actors.get(i), auction.id(), "intent-" + i, amounts.get(i));
            })).toList();
            start.countDown();
            for (var reply : replies) outcomes.add(reply.get(60, TimeUnit.SECONDS));
        }
        var accepted = outcomes.stream().filter(BidOutcome::accepted).sorted(Comparator.comparingLong(BidOutcome::auctionVersion)).toList();
        long previous = 90;
        Map<Long, Long> priceAtVersion = new HashMap<>();
        priceAtVersion.put(auction.version(), 90L);
        for (var result : accepted) {
            assertTrue(result.amountMinor() >= previous + 10);
            previous = result.amountMinor();
            priceAtVersion.put(result.auctionVersion(), previous);
        }
        for (var rejected : outcomes.stream().filter(o -> !o.accepted()).toList()) {
            assertEquals("BID_TOO_LOW", rejected.rejection());
            assertTrue(rejected.amountMinor() < priceAtVersion.get(rejected.auctionVersion()) + 10);
        }
        assertEquals(previous, service.get(buyer, auction.id()).highestBidAmountMinor());
        assertEquals(accepted.size(), jdbc.queryForObject("SELECT count(*) FROM bids WHERE auction_id=?", Integer.class, auction.id()));
    }
}
