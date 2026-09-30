package io.auctionhouse.experiments;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.auctionhouse.auction.*;
import io.auctionhouse.outbox.EventLog;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit experiment; never runs in the ordinary integration suite. */
@EnabledIfEnvironmentVariable(named="AUCTIONHOUSE_CONTENTION_EXPERIMENT", matches="true")
class ContentionExperimentTest {
    static final String IMAGE="postgres:18.6@sha256:5a5a84b19854a9ffaa54082c166ff4ec27473a361e496e5ea167f298f2da9722";
    static final ObjectMapper JSON=new ObjectMapper();
    static final int RUNS=integer("AUCTIONHOUSE_CONTENTION_RUNS",20,1,1000);
    static final int CLIENTS=integer("AUCTIONHOUSE_CONTENTION_CLIENTS",200,2,1000);
    static final int POOL=integer("AUCTIONHOUSE_CONTENTION_POOL",16,1,40);
    static final int ATTEMPTS=integer("AUCTIONHOUSE_CONTENTION_ATTEMPTS",8,1,20);
    static final long SEED=Long.parseLong(env("AUCTIONHOUSE_CONTENTION_SEED","20261001"));
    final Path output=Path.of(env("AUCTIONHOUSE_CONTENTION_OUTPUT","build/experiments/contention"));
    final List<Map<String,Object>> summaries=new ArrayList<>();
    final List<String> allViolations=new ArrayList<>();

    @Test void seededTwoServiceContentionAndIndependentCommittedHistoryOracle() throws Exception {
        Files.createDirectories(output);
        if(Files.exists(output.resolve("summary.json"))) throw new IllegalArgumentException("Choose a fresh output directory");
        var database=new PostgreSQLContainer(DockerImageName.parse("postgres@"+IMAGE.split("@",2)[1]).asCompatibleSubstituteFor("postgres"))
                .withCommand("postgres","-c","max_connections=100","-c","shared_buffers=128MB")
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(1L<<30).withNanoCPUs(2_000_000_000L));
        Instant began=Instant.now();
        try(database) {
            database.start();
            try(var pool1=pool(database,"contention-first");var pool2=pool(database,"contention-second")) {
                Flyway.configure().dataSource(pool1).load().migrate();
                warm(pool1);warm(pool2);
                var jdbc=new JdbcTemplate(pool1);
                write("environment.json",environment(jdbc,database,began));
                UUID seller=actor(jdbc,"seller");
                var actors=IntStream.range(0,CLIENTS).mapToObj(i -> actor(jdbc,"bidder-"+i)).toList();
                for(int run=0;run<RUNS;run++) {
                    var order=run%2==0?List.of(AuctionService.Isolation.ROW_LOCKS,AuctionService.Isolation.SERIALIZABLE)
                            :List.of(AuctionService.Isolation.SERIALIZABLE,AuctionService.Isolation.ROW_LOCKS);
                    for(var strategy:order) executeRun(run,strategy,seller,actors,pool1,pool2,jdbc);
                }
                var summary=new LinkedHashMap<String,Object>();
                summary.put("startedAt",began.toString());summary.put("finishedAt",Instant.now().toString());
                summary.put("runsPerStrategy",RUNS);summary.put("clientsPerRun",CLIENTS);
                summary.put("completedRuns",summaries.size());summary.put("requests",(long)summaries.size()*CLIENTS);
                summary.put("runSummaries",summaries);summary.put("oracleViolations",allViolations);
                summary.put("label","two service objects and separate pools in one JVM; not HTTP or two-process evidence");
                write("summary.json",summary);
            } finally {Files.writeString(output.resolve("postgres.log"),database.getLogs(),StandardCharsets.UTF_8);}
        }
        assertTrue(allViolations.isEmpty(),() -> "History oracle failures: "+allViolations);
    }

    void executeRun(int run,AuctionService.Isolation strategy,UUID seller,List<UUID> actors,
            HikariDataSource pool1,HikariDataSource pool2,JdbcTemplate jdbc) throws Exception {
        var tx1=new ObservedTransactions(new DataSourceTransactionManager(pool1));
        var tx2=new ObservedTransactions(new DataSourceTransactionManager(pool2));
        var jdbc1=new JdbcTemplate(pool1);var jdbc2=new JdbcTemplate(pool2);
        var first=new AuctionService(jdbc1,tx1,new EventLog(jdbc1),strategy,ATTEMPTS);
        var second=new AuctionService(jdbc2,tx2,new EventLog(jdbc2),strategy,ATTEMPTS);
        var services=List.of(first,second);var managers=List.of(tx1,tx2);
        long runSeed=SEED+run;
        var amounts=new ArrayList<>(IntStream.range(0,CLIENTS).mapToObj(i -> 100L+10L*i).toList());
        Collections.shuffle(amounts,new Random(runSeed));
        var opened=first.publish(seller,first.create(seller,"Contention "+strategy+" "+run,
                "Isolated service/database fixture",100,10,Instant.now().plusSeconds(3600)).id());
        var ready=new CountDownLatch(CLIENTS);var start=new CountDownLatch(1);
        var active=new AtomicInteger();var peak=new AtomicInteger();long[] release={0};
        List<RequestResult> results=new ArrayList<>();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=IntStream.range(0,CLIENTS).mapToObj(index -> executor.submit(() -> {
                int service=index%2;String key="run-"+run+"-bidder-"+index;
                ready.countDown();start.await();
                long began=System.nanoTime();var tx=managers.get(service);tx.beginObservation();
                peak.accumulateAndGet(active.incrementAndGet(),Math::max);
                BidOutcome outcome=null;String code=null,type=null,detail=null;
                try {outcome=services.get(service).bid(actors.get(index),opened.id(),key,amounts.get(index));}
                catch(RuntimeException failure) {
                    type=failure.getClass().getName();
                    code=failure instanceof AuctionException auction?auction.code():"UNEXPECTED_ERROR";
                    var trace=new StringWriter();failure.printStackTrace(new PrintWriter(trace));detail=trace.toString();
                } finally {active.decrementAndGet();}
                long ended=System.nanoTime();int attempts=tx.endObservation();
                return new RequestResult(index,service,actors.get(index),key,amounts.get(index),
                        began-release[0],ended-began,attempts,outcome,code,type,detail);
            })).toList();
            if(!ready.await(30,TimeUnit.SECONDS)) throw new IllegalStateException("Clients failed to reach release barrier");
            release[0]=System.nanoTime();start.countDown();
            for(var future:futures) results.add(future.get(180,TimeUnit.SECONDS));
        }
        long wallNanos=System.nanoTime()-release[0];
        var history=jdbc.query("SELECT id,actor_id,amount,auction_version,accepted_at FROM bids WHERE auction_id=? ORDER BY auction_version",
                (rs,row) -> new HistoryRow(rs.getObject("id",UUID.class),rs.getObject("actor_id",UUID.class),
                        rs.getLong("amount"),rs.getLong("auction_version"),rs.getTimestamp("accepted_at").toInstant().toString()),opened.id());
        var intentActors=jdbc.queryForList("SELECT actor_id FROM bid_intents WHERE auction_id=? ORDER BY actor_id",UUID.class,opened.id());
        var persisted=intentActors.stream().map(actor -> second.intent(actor,opened.id(),
                "run-"+run+"-bidder-"+actors.indexOf(actor)).orElseThrow()).toList();
        var events=jdbc.queryForList("SELECT event_id,aggregate_version,event_type FROM outbox_events WHERE aggregate_id=? ORDER BY aggregate_version",opened.id());
        var finalState=first.get(seller,opened.id());
        var violations=oracle(opened,results,history,persisted,events,finalState);
        jdbc.update("UPDATE auctions SET ends_at=clock_timestamp()-interval '1 second' WHERE id=?",opened.id());
        var closed=second.close(seller,opened.id());
        check(Objects.equals(closed.winnerId(),finalState.highestBidderId()),"closed winner differs from final bidder",violations);
        check(closed.version()==finalState.version()+1,"closure version did not advance exactly once",violations);
        var report=new LinkedHashMap<String,Object>();
        report.put("strategy",strategy.name());report.put("run",run);report.put("seed",runSeed);
        report.put("auctionId",opened.id().toString());report.put("initialVersion",opened.version());report.put("amounts",amounts);
        report.put("requests",results.stream().map(this::requestMap).toList());report.put("committedHistory",history);
        report.put("retainedIntents",persisted.stream().map(ContentionExperimentTest::outcomeMap).toList());report.put("outbox",events);
        report.put("finalVersion",finalState.version());report.put("finalPrice",finalState.highestBidAmountMinor());
        report.put("finalBidder",String.valueOf(finalState.highestBidderId()));report.put("closedWinner",String.valueOf(closed.winnerId()));
        report.put("oracleViolations",violations);
        long accepted=results.stream().filter(r -> r.outcome()!=null && r.outcome().accepted()).count();
        long rejected=results.stream().filter(r -> r.outcome()!=null && !r.outcome().accepted()).count();
        long errors=results.stream().filter(r -> r.outcome()==null).count();
        int retries=results.stream().mapToInt(r -> Math.max(0,r.attempts()-1)).sum();
        double[] latency=results.stream().mapToDouble(r -> r.latencyNanos()/1_000_000.0).sorted().toArray();
        var summary=new LinkedHashMap<String,Object>();
        summary.put("strategy",strategy.name());summary.put("run",run);summary.put("seed",runSeed);
        summary.put("accepted",accepted);summary.put("rejected",rejected);summary.put("errors",errors);
        summary.put("errorCodes",results.stream().filter(r -> r.errorCode()!=null).collect(
                java.util.stream.Collectors.groupingBy(RequestResult::errorCode,TreeMap::new,java.util.stream.Collectors.counting())));
        summary.put("transactionAttempts",results.stream().mapToInt(RequestResult::attempts).sum());summary.put("transactionRetries",retries);
        summary.put("requestsRetried",results.stream().filter(r -> r.attempts()>1).count());
        summary.put("maxAttempts",results.stream().mapToInt(RequestResult::attempts).max().orElse(0));
        summary.put("latencyMs",Map.of("min",latency[0],"p50",quantile(latency,.50),"p95",quantile(latency,.95),
                "p99",quantile(latency,.99),"max",latency[latency.length-1]));
        summary.put("wallMs",wallNanos/1_000_000.0);summary.put("peakActiveClients",peak.get());summary.put("oraclePassed",violations.isEmpty());
        report.put("summary",summary);summaries.add(summary);
        for(String violation:violations) allViolations.add(strategy+" run "+run+": "+violation);
        write(String.format(Locale.ROOT,"%s-run-%02d.json",strategy.name().toLowerCase(Locale.ROOT),run),report);
        write("progress.json",Map.of("completedRuns",summaries.size(),"runSummaries",summaries,"oracleViolations",allViolations));
        System.out.printf(Locale.ROOT,"CONTENTION strategy=%s run=%d clients=%d accepted=%d rejected=%d errors=%d retries=%d p95Ms=%.3f oracle=%s%n",
                strategy,run,CLIENTS,accepted,rejected,errors,retries,quantile(latency,.95),violations.isEmpty());
    }

    static List<String> oracle(AuctionSnapshot opened,List<RequestResult> requests,List<HistoryRow> history,
            List<BidOutcome> intents,List<Map<String,Object>> events,AuctionSnapshot current) {
        var failures=new ArrayList<String>();var prices=new HashMap<Long,Long>();prices.put(opened.version(),null);
        var byBid=new HashMap<UUID,HistoryRow>();var byActor=new HashMap<UUID,BidOutcome>();
        long version=opened.version();Long price=null;UUID highest=null;
        for(var row:history) {
            check(row.version()==++version,"accepted version gap",failures);
            check(row.amount()>=opened.openingPriceMinor() && (price==null || row.amount()-price>=opened.minimumIncrementMinor()),
                    "accepted price violates opening/minimum increment",failures);
            check(!row.actor().equals(opened.ownerId()),"owner accepted as bidder",failures);
            check(byBid.put(row.id(),row)==null,"duplicate bid ID",failures);
            price=row.amount();highest=row.actor();prices.put(row.version(),row.amount());
        }
        for(var intent:intents) {
            check(byActor.put(intent.actorId(),intent)==null,"duplicate actor intent",failures);
            check(prices.containsKey(intent.auctionVersion()),"outcome references unknown version",failures);
            if(intent.accepted()) {
                var row=byBid.get(intent.bidId());
                check(row!=null && row.actor().equals(intent.actorId()) && row.amount()==intent.amountMinor()
                        && row.version()==intent.auctionVersion(),"accepted outcome differs from bid",failures);
            } else {
                check("BID_TOO_LOW".equals(intent.rejection()),"unexpected rejection "+intent.rejection(),failures);
                Long at=prices.get(intent.auctionVersion());
                check(intent.amountMinor()<opened.openingPriceMinor() || (at!=null && intent.amountMinor()-at<opened.minimumIncrementMinor()),
                        "invalid rejection at observed version",failures);
                check(intent.bidId()==null,"rejection has a bid effect",failures);
            }
        }
        for(var request:requests) {
            var retained=byActor.get(request.actor());
            if(request.outcome()!=null) check(request.outcome().equals(retained),"reply differs from cross-instance retained outcome",failures);
            else check(retained==null,"failed request unexpectedly committed an outcome",failures);
            check(request.attempts()>=1 && request.attempts()<=ATTEMPTS,"retry bound exceeded",failures);
            if(retained!=null) check(retained.key().equals(request.key()) && retained.amountMinor()==request.amount(),"retained inputs differ",failures);
        }
        check(intents.size()==requests.stream().filter(r -> r.outcome()!=null).count(),"intent count differs from completed outcomes",failures);
        check(history.size()==intents.stream().filter(BidOutcome::accepted).count(),"bid count differs from accepted outcomes",failures);
        check(Objects.equals(price,current.highestBidAmountMinor()) && Objects.equals(highest,current.highestBidderId())
                && current.version()==version,"final state differs from history",failures);
        var eventVersions=events.stream().filter(e -> "bid.accepted".equals(e.get("event_type")))
                .map(e -> ((Number)e.get("aggregate_version")).longValue()).sorted().toList();
        check(eventVersions.equals(history.stream().map(HistoryRow::version).toList()),"bid/outbox versions differ",failures);
        check(events.size()==history.size()+2,"outbox total effect count differs",failures);return failures;
    }

    Map<String,Object> environment(JdbcTemplate jdbc,PostgreSQLContainer database,Instant start) throws Exception {
        var result=new LinkedHashMap<String,Object>();
        result.put("startedAt",start.toString());result.put("java",System.getProperty("java.runtime.version"));
        result.put("os",System.getProperty("os.name")+" "+System.getProperty("os.version")+" "+System.getProperty("os.arch"));
        result.put("processorsVisibleToJvm",Runtime.getRuntime().availableProcessors());result.put("jvmMaxHeapBytes",Runtime.getRuntime().maxMemory());
        result.put("timezone",TimeZone.getDefault().getID());result.put("image",IMAGE);result.put("containerId",database.getContainerId());
        result.put("postgres",jdbc.queryForObject("SELECT version()",String.class));
        result.put("postgresSettings",jdbc.queryForList("SELECT name,setting,unit FROM pg_settings WHERE name IN ('max_connections','shared_buffers','work_mem','max_parallel_workers_per_gather','jit') ORDER BY name"));
        result.put("containerLimits",Map.of("cpu",2,"memoryBytes",1L<<30));result.put("instances",2);
        result.put("instanceType","service objects with separate pools in one JVM");result.put("poolSizePerInstance",POOL);
        result.put("connectionTimeoutMs",5000);result.put("transactionAttemptBound",ATTEMPTS);
        result.put("runsPerStrategy",RUNS);result.put("clientsPerRun",CLIENTS);result.put("seed",SEED);
        result.put("warmup","Migrations and full pool warmup; no discarded bidding repetitions");
        result.put("backgroundActivity",env("AUCTIONHOUSE_CONTENTION_BACKGROUND","not recorded"));
        result.put("randomness","Inputs deterministic; OS scheduling and production retry jitter are not");
        result.put("command",env("AUCTIONHOUSE_CONTENTION_COMMAND","gradlew integrationTest --tests *ContentionExperimentTest with opt-in environment"));
        result.put("cwd",Path.of("").toAbsolutePath().toString());result.put("gitHead",command("git","rev-parse","HEAD"));
        result.put("gitStatus",command("git","status","--short"));var hashes=new TreeMap<String,String>();
        for(String name:List.of("build.gradle","gradle.lockfile","src/main/java/io/auctionhouse/auction/AuctionService.java",
                "src/main/java/io/auctionhouse/auction/domain/Auction.java","src/main/java/io/auctionhouse/outbox/EventLog.java",
                "src/integrationTest/java/io/auctionhouse/experiments/ContentionExperimentTest.java")) {
            Path p=Path.of(name);if(Files.exists(p)) hashes.put(name,sha(p));
        }
        try(var paths=Files.list(Path.of("src/main/resources/db/migration"))) {
            for(Path p:paths.filter(Files::isRegularFile).toList()) hashes.put(p.toString(),sha(p));
        }
        result.put("inputSha256",hashes);return result;
    }
    static String sha(Path p) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));}
    static String command(String... args) throws Exception {
        var process=new ProcessBuilder(args).redirectErrorStream(true).start();
        String out=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        if(process.waitFor()!=0) throw new IllegalStateException("Metadata command failed");return out.strip();
    }
    static HikariDataSource pool(PostgreSQLContainer database,String name) {
        var config=new HikariConfig();config.setJdbcUrl(database.getJdbcUrl());config.setUsername(database.getUsername());
        config.setPassword(database.getPassword());config.setPoolName(name);config.setMaximumPoolSize(POOL);
        config.setMinimumIdle(POOL);config.setConnectionTimeout(5000);config.setInitializationFailTimeout(10000);
        return new HikariDataSource(config);
    }
    static void warm(HikariDataSource pool) throws Exception {
        var connections=new ArrayList<Connection>();
        try {for(int i=0;i<POOL;i++) connections.add(pool.getConnection());}
        finally {for(var connection:connections) connection.close();}
    }
    static UUID actor(JdbcTemplate jdbc,String subject) {
        UUID id=UUID.nameUUIDFromBytes(("contention:"+SEED+":"+subject).getBytes(StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO accounts(id,issuer,subject,display_name) VALUES(?, 'contention-experiment', ?, ?)",id,subject,subject);return id;
    }
    Map<String,Object> requestMap(RequestResult r) {
        var map=new LinkedHashMap<String,Object>();map.put("index",r.index());map.put("service",r.service());
        map.put("actor",r.actor().toString());map.put("key",r.key());map.put("amountMinor",r.amount());
        map.put("startedAfterReleaseNanos",r.startOffsetNanos());map.put("latencyNanos",r.latencyNanos());
        map.put("transactionAttempts",r.attempts());map.put("transactionRetries",Math.max(0,r.attempts()-1));
        map.put("outcome",r.outcome()==null?null:outcomeMap(r.outcome()));
        map.put("errorCode",r.errorCode());map.put("errorType",r.errorType());map.put("errorDetail",r.errorDetail());return map;
    }
    static Map<String,Object> outcomeMap(BidOutcome o) {
        var map=new LinkedHashMap<String,Object>();map.put("actorId",o.actorId().toString());map.put("auctionId",o.auctionId().toString());
        map.put("key",o.key());map.put("amountMinor",o.amountMinor());map.put("accepted",o.accepted());map.put("rejection",o.rejection());
        map.put("bidId",o.bidId()==null?null:o.bidId().toString());map.put("auctionVersion",o.auctionVersion());map.put("decidedAt",o.decidedAt().toString());return map;
    }
    void write(String file,Object value) throws Exception {
        Files.writeString(output.resolve(file),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value)+"\n",StandardCharsets.UTF_8);
    }
    static void check(boolean valid,String message,List<String> failures) {if(!valid) failures.add(message);}
    static double quantile(double[] ordered,double p) {return ordered[Math.max(0,(int)Math.ceil(p*ordered.length)-1)];}
    static String env(String name,String fallback) {return System.getenv().getOrDefault(name,fallback);}
    static int integer(String name,int fallback,int min,int max) {
        int value=Integer.parseInt(env(name,Integer.toString(fallback)));if(value<min||value>max) throw new IllegalArgumentException(name+" out of range");return value;
    }
    record RequestResult(int index,int service,UUID actor,String key,long amount,long startOffsetNanos,long latencyNanos,
            int attempts,BidOutcome outcome,String errorCode,String errorType,String errorDetail) {}
    record HistoryRow(UUID id,UUID actor,long amount,long version,String acceptedAt) {}
    static final class ObservedTransactions implements PlatformTransactionManager {
        final PlatformTransactionManager delegate;final ThreadLocal<int[]> count=new ThreadLocal<>();
        ObservedTransactions(PlatformTransactionManager delegate) {this.delegate=delegate;}
        void beginObservation() {count.set(new int[1]);}
        int endObservation() {int value=count.get()[0];count.remove();return value;}
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            var current=count.get();if(current!=null) current[0]++;return delegate.getTransaction(definition);
        }
        public void commit(TransactionStatus status) {delegate.commit(status);}
        public void rollback(TransactionStatus status) {delegate.rollback(status);}
    }
}
