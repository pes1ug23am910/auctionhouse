package io.auctionhouse.outbox;

import static org.junit.jupiter.api.Assertions.*;
import io.auctionhouse.auction.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.*;
import org.apache.kafka.clients.admin.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@EnabledIfSystemProperty(named="auctionhouse.brokerExperiment",matches="true")
class BrokerCrashExperimentTest {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName.parse("postgres@sha256:5a5a84b19854a9ffaa54082c166ff4ec27473a361e496e5ea167f298f2da9722").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager manager;
    static String brokers;
    static Path evidence;
    static AdminClient admin;
    static Stream<Arguments> boundaries() {
        return IntStream.rangeClosed(1,2).boxed().flatMap(run -> Stream.of("BEFORE_BID","BID_WRITE_BEFORE_COMMIT",
            "BID_COMMIT_BEFORE_RESPONSE","BEFORE_BROKER_SEND","BROKER_ACK_BEFORE_MARK","RELAY_COMMIT",
            "BEFORE_SINK_EFFECT","SINK_EFFECT_BEFORE_COMMIT","SINK_COMMIT_BEFORE_ACK","CONSUMER_ACK")
            .map(boundary -> Arguments.of(run,boundary)));
    }
    @BeforeAll static void setup() throws Exception {
        var source=new PGSimpleDataSource(); source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername()); source.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);
        brokers=System.getProperty("auctionhouse.brokers","127.0.0.1:19092");
        evidence=Path.of(System.getProperty("auctionhouse.experimentOutput","build/experiments/broker"));
        Files.createDirectories(evidence);
        admin=AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,brokers));
        admin.describeCluster().nodes().get(15,TimeUnit.SECONDS);
    }
    @AfterAll static void finish() { if (admin!=null) admin.close(Duration.ofSeconds(5)); }
    @ParameterizedTest(name="run {0}: {1}") @MethodSource("boundaries")
    void abruptWorkerLossRecoversTheCommittedSet(int run,String boundary) throws Exception {
        jdbc.execute("TRUNCATE accounts CASCADE");
        jdbc.execute("TRUNCATE notification_effects,notification_deliveries,event_cuts CASCADE");
        UUID seller=UUID.randomUUID(),actor=UUID.randomUUID();
        for (UUID id:List.of(seller,actor)) jdbc.update("INSERT INTO accounts(id,issuer,subject,display_name) VALUES(?,'crash',?,'Crash fixture')",id,id.toString());
        var auctions=new AuctionService(jdbc,manager,new EventLog(jdbc),AuctionService.Isolation.ROW_LOCKS,8);
        var draft=auctions.create(seller,"Crash fixture","",100,10,Instant.now().plusSeconds(900));
        var auction=auctions.publish(seller,draft.id());
        String topic="ah-crash-"+UUID.randomUUID();
        admin.createTopics(List.of(new NewTopic(topic,1,(short)1).configs(Map.of("min.insync.replicas","1","retention.ms","3600000")))).all().get(15,TimeUnit.SECONDS);
        String name=run+"-"+boundary;
        try {
            assertEquals(73,worker(boundary,actor,auction.id(),topic,evidence.resolve(name+"-crash.log")));
            assertEquals(0,worker("RECOVER_BID",actor,auction.id(),topic,evidence.resolve(name+"-bid-recovery.log")));
            var reconciliation=new EventReconciliation(jdbc,manager);
            UUID cut=reconciliation.capture();
            assertEquals(0,worker("RECOVER_ALL",actor,auction.id(),topic,evidence.resolve(name+"-delivery-recovery.log")));
            var report=reconciliation.report(cut);
            assertEquals(3,report.expected());
            assertTrue(report.missing().isEmpty());
            assertTrue(report.unexpected().isEmpty());
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM bids WHERE auction_id=?",Integer.class,auction.id()));
            assertEquals(150,auctions.get(actor,auction.id()).highestBidAmountMinor());
            assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM notification_effects",Integer.class));
            if (Set.of("BROKER_ACK_BEFORE_MARK","SINK_COMMIT_BEFORE_ACK").contains(boundary))
                assertFalse(report.duplicateAttempts().isEmpty(),"The deliberate redelivery must be observed");
            var json=new tools.jackson.databind.ObjectMapper();
            Files.writeString(evidence.resolve(name+"-reconciliation.json"),json.writeValueAsString(Map.of(
                "run",run,"boundary",boundary,"report",report,"events",reconciliation.events(cut,null,100),
                "deliveries",jdbc.queryForList("SELECT event_id,duplicate FROM notification_deliveries ORDER BY id"))),StandardCharsets.UTF_8);
        } finally { admin.deleteTopics(List.of(topic)).all().get(15,TimeUnit.SECONDS); }
    }
    private int worker(String boundary,UUID actor,UUID auction,String topic,Path log) throws Exception {
        String classpath=System.getProperty("auctionhouse.integrationClasspath");
        assertNotNull(classpath,"Gradle must supply integration runtime classpath");
        Path arguments=Files.createTempFile("auctionhouse-crash-", ".args");
        try {
            Files.writeString(arguments,"-Xmx128m\n-Duser.timezone=UTC\n-cp\n\""+classpath.replace("\\","/")+"\"\n"+
                DeliveryCrashWorker.class.getName()+"\n"+boundary+"\n"+actor+"\n"+auction+"\n");
            var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"@"+arguments);
            builder.environment().putAll(Map.of("CRASH_DB_URL",POSTGRES.getJdbcUrl(),"CRASH_DB_USER",POSTGRES.getUsername(),
                "CRASH_DB_PASSWORD",POSTGRES.getPassword(),"CRASH_BROKERS",brokers,"CRASH_TOPIC",topic));
            builder.redirectErrorStream(true).redirectOutput(log.toFile());
            var process=builder.start();
            if (!process.waitFor(65,TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Crash worker timed out; see "+log); }
            return process.exitValue();
        } finally { Files.deleteIfExists(arguments); }
    }
}
