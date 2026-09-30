package io.auctionhouse.outbox;

import static org.junit.jupiter.api.Assertions.*;
import io.auctionhouse.auction.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Explicitly selected local experiment; restarts only the verified project broker. */
@Testcontainers
@EnabledIfSystemProperty(named="auctionhouse.brokerOutage",matches="true")
class BrokerOutageExperimentTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres@sha256:5a5a84b19854a9ffaa54082c166ff4ec27473a361e496e5ea167f298f2da9722")
                    .asCompatibleSubstituteFor("postgres"));

    @Test void brokerProcessLossBoundsRelayWorkWhileBidsCommitAndDeliveryRecovers() throws Exception {
        String brokers = System.getProperty("auctionhouse.brokers", "127.0.0.1:19092");
        String container = System.getProperty("auctionhouse.brokerContainer", "auctionhouse-redpanda-1");
        var docker = DockerClientFactory.instance().client();
        var inspected = docker.inspectContainerCmd(container).exec();
        assertEquals("auctionhouse", inspected.getConfig().getLabels().get("com.docker.compose.project"));
        assertEquals("redpanda", inspected.getConfig().getLabels().get("com.docker.compose.service"));
        assertEquals("127.0.0.1:19092", brokers, "this experiment only interrupts the declared local Redpanda profile");
        String containerId = inspected.getId();
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl()); dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        var manager = new DataSourceTransactionManager(dataSource);
        var auctions = new AuctionService(jdbc, manager, new EventLog(jdbc), AuctionService.Isolation.ROW_LOCKS, 8);
        UUID seller = UUID.randomUUID(), buyer = UUID.randomUUID();
        for (UUID id : List.of(seller, buyer)) jdbc.update(
                "INSERT INTO accounts(id,issuer,subject,display_name) VALUES(?,'broker-outage',?,'Outage fixture')", id, id.toString());
        var draft = auctions.create(seller, "Outage fixture", "", 100, 10, Instant.now().plusSeconds(600));
        var auction = auctions.publish(seller, draft.id());
        String topic = "ah-outage-" + UUID.randomUUID();
        var producerProperties = new HashMap<String,Object>();
        producerProperties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProperties.put(ProducerConfig.ACKS_CONFIG, "all");
        producerProperties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        producerProperties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3000);
        producerProperties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000);
        producerProperties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5000);
        var factory = new DefaultKafkaProducerFactory<String,String>(producerProperties);
        boolean interrupted = false;
        try (var admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, brokers));
                var executor = Executors.newSingleThreadExecutor()) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short)1).configs(
                    Map.of("min.insync.replicas", "1", "retention.ms", "3600000")))).all().get(15, TimeUnit.SECONDS);
            var publisher = new KafkaEventPublisher(new KafkaTemplate<>(factory), topic);
            var startedSend = new CountDownLatch(1);
            var observeOutage = new AtomicBoolean();
            var relay = new OutboxRelay(jdbc, manager, message -> {
                if (observeOutage.get()) startedSend.countDown();
                return publisher.publish(message);
            }, 4);
            assertEquals(2, relay.once());
            docker.killContainerCmd(containerId).withSignal("KILL").exec();
            interrupted = true;
            for (int i=0; i<10; i++) assertTrue(auctions.bid(buyer, auction.id(), "outage-" + i, 100 + i * 10).accepted());
            observeOutage.set(true);
            long relayStarted = System.nanoTime();
            var blockedRelay = executor.submit(relay::once);
            assertTrue(startedSend.await(5, TimeUnit.SECONDS));
            long bidStarted = System.nanoTime();
            assertTrue(auctions.bid(buyer, auction.id(), "outage-concurrent", 200).accepted());
            double bidMilliseconds = (System.nanoTime() - bidStarted) / 1_000_000.0;
            assertTrue(bidMilliseconds < 5000, "broker publication must not hold the auction row");
            assertEquals(0, blockedRelay.get(30, TimeUnit.SECONDS));
            double relayMilliseconds = (System.nanoTime() - relayStarted) / 1_000_000.0;
            assertTrue(relayMilliseconds < 30_000);
            int attempted = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL AND attempts>0", Integer.class);
            assertTrue(attempted >= 1 && attempted <= 4);
            assertEquals(11, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Integer.class));
            int untouched = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL AND attempts=0", Integer.class);
            assertEquals(11 - attempted, untouched);
            var cuts = new EventReconciliation(jdbc, manager);
            UUID cut = cuts.capture();
            docker.startContainerCmd(containerId).exec();
            interrupted = false;
            long recoveryStarted = System.nanoTime();
            long deadline = recoveryStarted + Duration.ofSeconds(75).toNanos();
            boolean ready = false;
            while (System.nanoTime() < deadline && !ready) {
                try { admin.describeCluster().nodes().get(2, TimeUnit.SECONDS); ready = true; }
                catch (Exception unavailable) { Thread.sleep(250); }
            }
            assertTrue(ready, "broker failed to restart");
            while (System.nanoTime() < deadline && jdbc.queryForObject(
                    "SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Integer.class) > 0) {
                relay.once(); Thread.sleep(100);
            }
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Integer.class));
            var consumerProperties = new HashMap<String,Object>();
            consumerProperties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
            consumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG, topic + "-sink");
            consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            consumerProperties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            consumerProperties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
            consumerProperties.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 6000);
            consumerProperties.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 1000);
            var notification = new NotificationConsumer(new NotificationSink(jdbc, manager,
                    new tools.jackson.databind.ObjectMapper()), new EventQuarantine(jdbc));
            boolean drained = false;
            try (var consumer = new KafkaConsumer<String,String>(consumerProperties)) {
                consumer.subscribe(List.of(topic));
                while (System.nanoTime() < deadline) {
                    for (var record : consumer.poll(Duration.ofMillis(200)))
                        notification.receive(record, () -> consumer.commitSync());
                    if (!consumer.assignment().isEmpty() && cuts.report(cut).missing().isEmpty()) {
                        var ends = consumer.endOffsets(consumer.assignment());
                        drained = true;
                        for (var end : ends.entrySet()) if (consumer.position(end.getKey()) < end.getValue()) drained = false;
                        if (drained) break;
                    }
                }
            }
            var report = cuts.report(cut);
            assertTrue(drained);
            assertEquals(13, report.expected());
            assertTrue(report.missing().isEmpty(), "includes the two broker-acknowledged events before abrupt restart");
            assertTrue(report.unexpected().isEmpty());
            assertEquals(13, jdbc.queryForObject("SELECT count(*) FROM notification_effects", Integer.class));
            assertEquals(11, jdbc.queryForObject("SELECT count(*) FROM bids", Integer.class));
            Path output = Path.of(System.getProperty("auctionhouse.experimentOutput", "build/experiments/broker"));
            Files.createDirectories(output);
            Files.writeString(output.resolve("broker-outage-reconciliation.json"), new tools.jackson.databind.ObjectMapper()
                    .writeValueAsString(Map.of("broker", "Redpanda", "fault", "SIGKILL broker process; persistent local volume",
                            "replicas", 1, "bidDuringOutageMs", bidMilliseconds, "failedRelayMs", relayMilliseconds,
                            "attemptedRows", attempted, "untouchedRows", untouched,
                            "recoveryMs", (System.nanoTime() - recoveryStarted) / 1_000_000.0, "report", report)));
            admin.deleteTopics(List.of(topic)).all().get(15, TimeUnit.SECONDS);
        } finally {
            if (interrupted) docker.startContainerCmd(containerId).exec();
            factory.destroy();
        }
    }
}
