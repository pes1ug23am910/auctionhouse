package io.auctionhouse.outbox;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import io.auctionhouse.auction.*;

@Testcontainers
class DeliveryIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres@sha256:5a5a84b19854a9ffaa54082c166ff4ec27473a361e496e5ea167f298f2da9722").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager manager;

    @BeforeAll static void database() {
        var source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl()); source.setUser(POSTGRES.getUsername()); source.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source);
    }
    @BeforeEach void reset() {
        jdbc.update("DELETE FROM notification_deliveries"); jdbc.update("DELETE FROM notification_effects");
        jdbc.update("DELETE FROM event_cut_members"); jdbc.update("DELETE FROM event_cuts"); jdbc.update("DELETE FROM outbox_events");
    }
    void event() {
        UUID actor = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts(id,issuer,subject,display_name) VALUES(?,'delivery',?,'Delivery test')",actor,actor.toString());
        new AuctionService(jdbc,manager,new EventLog(jdbc),AuctionService.Isolation.ROW_LOCKS,3)
                .create(actor,"Delivery fixture","",100,10,Instant.now().plusSeconds(60));
    }
    @Test void elapsedAttemptBudgetCommitsBackoffAndLeavesUnattemptedRowsForAnotherRun() {
        event(); event(); event();
        var clock = new java.util.concurrent.atomic.AtomicLong();
        var attempts = new AtomicInteger();
        EventPublisher unavailable = message -> {
            attempts.incrementAndGet();
            clock.addAndGet(java.time.Duration.ofSeconds(21).toNanos());
            throw new IllegalStateException("bounded publisher deadline elapsed");
        };
        var relay = new OutboxRelay(jdbc, manager, unavailable, 100,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), clock::get);
        assertEquals(0, relay.once());
        assertEquals(1, attempts.get(), "the remaining claimed rows must not start another timed-out send");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE attempts=1 AND next_attempt_at>clock_timestamp() AND published_at IS NULL", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE attempts=0 AND published_at IS NULL", Integer.class));
        assertEquals(0, relay.once());
        assertEquals(2, attempts.get());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE attempts=0", Integer.class));
    }

    @Test void acknowledgementLostAfterBrokerAcceptCausesDuplicateDeliveryButOneSinkEffect() {
        event();
        List<OutboxMessage> delivered = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        EventPublisher publisher = message -> {
            delivered.add(message);
            if (attempts.getAndIncrement() == 0) throw new IllegalStateException("acknowledgement lost");
            return new EventPublisher.Receipt(0,1);
        };
        var relay = new OutboxRelay(jdbc,manager,publisher,16);
        assertEquals(0,relay.once());
        jdbc.update("UPDATE outbox_events SET next_attempt_at=clock_timestamp()");
        assertEquals(1,relay.once());
        assertEquals(2,delivered.size());
        var sink = new NotificationSink(jdbc,manager,new tools.jackson.databind.ObjectMapper());
        assertTrue(sink.accept(delivered.getFirst().envelope()));
        assertFalse(sink.accept(delivered.getLast().envelope()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM notification_effects",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM notification_deliveries WHERE duplicate",Integer.class));
    }
    @Test void errorAfterPublishingRollsBackRelayMarkAndRestartRepublishes() {
        event();
        var delivered = new ArrayList<OutboxMessage>();
        EventPublisher crashing = message -> { delivered.add(message); throw new SimulatedProcessLoss(); };
        assertThrows(SimulatedProcessLoss.class,() -> new OutboxRelay(jdbc,manager,crashing,16).once());
        assertEquals(1,new OutboxRelay(jdbc,manager,message -> { delivered.add(message); return new EventPublisher.Receipt(0,2); },16).once());
        assertEquals(delivered.getFirst().eventId(),delivered.getLast().eventId());
    }
    @Test void sourceCutIsAnExplicitImmutableSetAndDoesNotAdoptLaterEvents() {
        event();
        var cuts = new EventReconciliation(jdbc,manager);
        var cut = cuts.capture();
        event();
        assertEquals(1,cuts.events(cut,null,100).size());
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM outbox_events",Integer.class));
        assertEquals(1,cuts.report(cut).missing().size());
    }

    @Test @Timeout(20)
    void sourceCutExcludesAnEarlierAllocatedUncommittedEventUntilTheNextCut() throws Exception {
        var cuts = new EventReconciliation(jdbc, manager);
        var allocated = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var early = new AtomicReference<UUID>();
        var earlyTime = new AtomicReference<Instant>();
        UUID firstCut;
        UUID later;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = executor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(manager)
                    .execute(status -> {
                        event();
                        early.set(jdbc.queryForObject("SELECT event_id FROM outbox_events", UUID.class));
                        earlyTime.set(jdbc.queryForObject("SELECT occurred_at FROM outbox_events",
                                (rs, row) -> rs.getTimestamp(1).toInstant()));
                        allocated.countDown();
                        try {
                            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture did not release early event");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Fixture interrupted", interrupted);
                        }
                        return early.get();
                    }));
            try {
                assertTrue(allocated.await(5, TimeUnit.SECONDS));
                assertFalse(pending.isDone(), "the earlier event's business transaction remains uncommitted");
                event();
                later = jdbc.queryForObject("SELECT event_id FROM outbox_events", UUID.class);
                Instant laterTime = jdbc.queryForObject("SELECT occurred_at FROM outbox_events",
                        (rs, row) -> rs.getTimestamp(1).toInstant());
                assertNotEquals(early.get(), later);
                assertTrue(earlyTime.get().isBefore(laterTime));
                // UUID identities have no global sequence. The earlier timestamp must not be mistaken for a committed watermark.
                firstCut = cuts.capture();
                assertEquals(Set.of(later), eventIds(cuts, firstCut));
                assertEquals(List.of(later), cuts.report(firstCut).missing());
                release.countDown();
                assertEquals(early.get(), pending.get(5, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
        }
        assertEquals(Set.of(later), eventIds(cuts, firstCut), "a later commit must never mutate an existing source cut");
        UUID nextCut = cuts.capture();
        assertEquals(Set.of(early.get(), later), eventIds(cuts, nextCut));
        var sink = new NotificationSink(jdbc, manager, new tools.jackson.databind.ObjectMapper());
        for (var message : cuts.events(nextCut, null, 10)) assertTrue(sink.accept(message.envelope()));
        assertEquals(1, cuts.report(firstCut).expected());
        assertEquals(2, cuts.report(nextCut).expected());
        assertTrue(cuts.report(firstCut).missing().isEmpty());
        assertTrue(cuts.report(nextCut).missing().isEmpty());
        assertTrue(cuts.report(firstCut).unexpected().isEmpty());
        assertEquals(1, cuts.notifications(firstCut, null, 10).size());
        assertEquals(2, cuts.notifications(nextCut, null, 10).size());
    }

    private static Set<UUID> eventIds(EventReconciliation cuts, UUID cut) {
        var ids = new HashSet<UUID>();
        for (var message : cuts.events(cut, null, 10)) ids.add(message.eventId());
        return ids;
    }
    @Test void unsupportedSchemasAndIdentityConflictsNeverCreateEffects() {
        event();
        var cuts = new EventReconciliation(jdbc,manager);
        var envelope = cuts.events(cuts.capture(),null,10).getFirst();
        var sink = new NotificationSink(jdbc,manager,new tools.jackson.databind.ObjectMapper());
        String valid = envelope.envelope();
        assertTrue(sink.accept(valid));
        assertThrows(IllegalArgumentException.class,() -> sink.accept(valid.replace("auction.created","auction.published")));
        assertThrows(IllegalArgumentException.class,() -> sink.accept(valid.replace("\"schemaVersion\": 1","\"schemaVersion\": 9")));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM notification_effects",Integer.class));
    }
    @Test void malformedInputIsQuarantinedBeforeAcknowledgementAndReplayIsHarmless() {
        var sink = new NotificationSink(jdbc,manager,new tools.jackson.databind.ObjectMapper());
        var consumer = new NotificationConsumer(sink,new EventQuarantine(jdbc));
        var record = new org.apache.kafka.clients.consumer.ConsumerRecord<String,String>("invalid-test",0,7,"key","{broken");
        AtomicInteger acknowledgements = new AtomicInteger();
        consumer.receive(record,acknowledgements::incrementAndGet);
        consumer.receive(record,acknowledgements::incrementAndGet);
        assertEquals(2, acknowledgements.get());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM event_quarantine WHERE topic='invalid-test'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM notification_effects",Integer.class));
    }

    @Test void persistedTraceContextResumesAtTheRelayWithoutChangingTheEnvelope() {
        String traceId="0123456789abcdef0123456789abcdef";
        var parent=io.opentelemetry.api.trace.SpanContext.create(traceId,"0123456789abcdef",
                io.opentelemetry.api.trace.TraceFlags.getSampled(),io.opentelemetry.api.trace.TraceState.getDefault());
        try(var scope=io.opentelemetry.api.trace.Span.wrap(parent).makeCurrent()) {event();}
        String stored=jdbc.queryForObject("SELECT trace_parent FROM outbox_events",String.class);
        assertNotNull(stored);assertTrue(stored.startsWith("00-"+traceId+"-"));
        var captured=new ArrayList<String>();
        var relay=new OutboxRelay(jdbc,manager,message -> {
            captured.add(io.opentelemetry.api.trace.Span.current().getSpanContext().getTraceId());
            assertFalse(message.envelope().contains("traceparent"));
            return new EventPublisher.Receipt(0,1);
        },16);
        assertEquals(1,relay.once());assertEquals(List.of(traceId),captured);
        assertFalse(io.opentelemetry.api.trace.Span.current().getSpanContext().isValid());
    }

    @Test void malformedFieldTypesAndPayloadIdentityAreQuarantinedWithoutEffects() {
        event();
        var cuts=new EventReconciliation(jdbc,manager);
        String valid=cuts.events(cuts.capture(),null,10).getFirst().envelope();
        var json=new tools.jackson.databind.ObjectMapper();
        Map<String,Object> source=json.readValue(valid,new tools.jackson.core.type.TypeReference<Map<String,Object>>() {});
        var sink=new NotificationSink(jdbc,manager,json);
        var consumer=new NotificationConsumer(sink,new EventQuarantine(jdbc));
        List<Map.Entry<String,Object>> changes=List.of(
                Map.entry("schemaVersion",1.1),Map.entry("schemaVersion","1"),
                Map.entry("aggregateVersion",1.1),Map.entry("aggregateVersion","1"),
                Map.entry("eventType",Map.of()),Map.entry("eventId",false),
                Map.entry("aggregateId",List.of()),Map.entry("occurredAt",Map.of()),
                Map.entry("payload",Map.of("id",UUID.randomUUID().toString(),"version",1)),
                Map.entry("payload",Map.of("id",source.get("aggregateId"),"version","1")));
        AtomicInteger acknowledged=new AtomicInteger();
        long offset=0;
        for(var change:changes) {
            Map<String,Object> invalid=new HashMap<>(source);invalid.put(change.getKey(),change.getValue());
            var record=new org.apache.kafka.clients.consumer.ConsumerRecord<String,String>(
                    "invalid-shape-test",0,offset++,"key",json.writeValueAsString(invalid));
            assertDoesNotThrow(() -> consumer.receive(record,acknowledged::incrementAndGet));
        }
        assertEquals(changes.size(),acknowledged.get());
        assertEquals(changes.size(),jdbc.queryForObject("SELECT count(*) FROM event_quarantine WHERE topic='invalid-shape-test'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM notification_effects",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM notification_deliveries",Integer.class));
    }

    static final class SimulatedProcessLoss extends Error { }
}
