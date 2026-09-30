package io.auctionhouse.web;

import io.auctionhouse.auction.*;
import io.auctionhouse.auth.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@Timeout(40)
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "auctionhouse.auth.secure-cookies=false","auctionhouse.closure.enabled=false",
    "auctionhouse.stream.max-clients=2","auctionhouse.stream.replay-window=10000",
    "auctionhouse.stream.max-queued-frames=16","auctionhouse.stream.max-queued-bytes=65536",
    "auctionhouse.stream.stall-timeout-ms=350","auctionhouse.stream.max-lifetime-ms=30000"})
class AuctionStreamIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(
        org.testcontainers.utility.DockerImageName.parse("postgres@sha256:5a5a84b19854a9ffaa54082c166ff4ec27473a361e496e5ea167f298f2da9722").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        p.add("spring.datasource.username",POSTGRES::getUsername);
        p.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Value("${local.server.port}") int port;
    @Autowired AuctionService auctions;
    @Autowired AuctionStreams streams;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    final List<TokenPair> credentials=new ArrayList<>();
    final List<AutoCloseable> connections=new ArrayList<>();
    AuctionPrincipal owner,bidder;
    TokenPair ownerToken,bidderToken;
    HttpClient http;

    @BeforeEach void setup() {
        owner=account("owner"); bidder=account("bidder");
        ownerToken=issue(owner); bidderToken=issue(bidder);
        http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }
    @AfterEach void cleanup() throws Exception {
        for (var pair:credentials) auth.logout(pair.refreshToken(),pair.accessToken());
        for (var connection:connections) try { connection.close(); } catch (Exception ignored) { }
        await(()->streams.activeClients()==0,5000);
        http.close();
    }
    AuctionPrincipal account(String name) {
        return auth.findOrCreateAccount("https://stream-fixture.test",UUID.randomUUID().toString(),name);
    }
    TokenPair issue(AuctionPrincipal actor) { var pair=auth.issue(actor);credentials.add(pair);return pair; }
    AuctionSnapshot opened(String description) {
        var draft=auctions.create(owner.id(),"Stream fixture",description,100,10,Instant.now().plusSeconds(300));
        return auctions.publish(owner.id(),draft.id());
    }
    URI uri(UUID auction,String query) { return URI.create("http://127.0.0.1:"+port+"/api/auctions/"+auction+"/events"+query); }
    HttpRequest request(UUID auction,TokenPair token,String query) {
        return HttpRequest.newBuilder(uri(auction,query)).timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+token.accessToken()).GET().build();
    }
    Connection connect(UUID auction,TokenPair token,String cursor) throws Exception {
        var builder=HttpRequest.newBuilder(uri(auction,"")).timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+token.accessToken());
        if (cursor!=null) builder.header("Last-Event-ID",cursor);
        var response=http.send(builder.GET().build(),HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200,response.statusCode(),"stream status");
        var connection=new Connection(response.body());connections.add(connection);return connection;
    }

    @Test void consistentSnapshotAndReconnectResumeOnlyMissingCommittedVersion() throws Exception {
        var auction=opened("Resume fixture");
        var first=connect(auction.id(),bidderToken,null);
        var snapshot=json.readTree(first.event("snapshot",5000).data());
        assertEquals(snapshot.get("auction").get("version").asLong(),2);
        assertEquals("v1:"+auction.id()+":2",snapshot.get("cursor").asString());
        var accepted=auctions.bid(bidder.id(),auction.id(),"stream-first",100);
        var event=first.event("auction",5000);
        assertEquals(accepted.auctionVersion(),json.readTree(event.data()).get("aggregateVersion").asLong());
        first.close();
        var secondAccepted=auctions.bid(bidder.id(),auction.id(),"stream-second",110);
        var resumed=connect(auction.id(),bidderToken,event.id());
        var later=resumed.event("auction",5000);
        assertEquals("v1:"+auction.id()+":"+secondAccepted.auctionVersion(),later.id());
        assertEquals(110,json.readTree(later.data()).get("payload").get("highestBidAmountMinor").asLong());
    }

    @Test void aCommitBetweenHttpSnapshotAndSubscriptionIsReplayedBeforeSubsequentLiveBids() throws Exception {
        var auction = opened("Snapshot subscription boundary");
        var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/auctions/" + auction.id() + "/snapshot"))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + bidderToken.accessToken())
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var snapshot = json.readTree(response.body());
        assertEquals(2, snapshot.get("auction").get("version").asLong());
        String cursor = snapshot.get("cursor").asString();
        assertEquals("v1:" + auction.id() + ":2", cursor);
        // This transaction commits inside the client's snapshot/subscription window, before any stream exists.
        var between = auctions.bid(bidder.id(), auction.id(), "between-snapshot-and-subscribe", 100);
        var viewer = connect(auction.id(), bidderToken, cursor);
        var replayed = nextFrame(viewer);
        assertEquals("auction", replayed.type());
        assertEquals("v1:" + auction.id() + ":" + between.auctionVersion(), replayed.id());
        var replayedEvent = json.readTree(replayed.data());
        assertEquals(between.auctionVersion(), replayedEvent.get("payload").get("version").asLong());
        assertEquals(100, replayedEvent.get("payload").get("highestBidAmountMinor").asLong());
        var subsequent = auctions.bid(bidder.id(), auction.id(), "after-subscribe", 110);
        var live = nextFrame(viewer);
        assertEquals("auction", live.type());
        assertEquals("v1:" + auction.id() + ":" + subsequent.auctionVersion(), live.id());
        var liveEvent = json.readTree(live.data());
        assertEquals(subsequent.auctionVersion(), liveEvent.get("payload").get("version").asLong());
        assertEquals(110, liveEvent.get("payload").get("highestBidAmountMinor").asLong());
        assertEquals(jdbc.queryForList("SELECT event_id::text FROM outbox_events WHERE aggregate_id=? "
                        + "AND aggregate_version>? ORDER BY aggregate_version", String.class, auction.id(), 2),
                List.of(replayedEvent.get("eventId").asString(), liveEvent.get("eventId").asString()));
    }

    @Test void aMissingRetainedVersionRecoversOneConsistentSnapshotThenResumesLiveDelivery() throws Exception {
        var auction = opened("Missing retained transition");
        auctions.bid(bidder.id(), auction.id(), "gap-first", 100);
        auctions.bid(bidder.id(), auction.id(), "gap-second", 110);
        var last = auctions.bid(bidder.id(), auction.id(), "gap-third", 120);
        // Simulate unavailable retained history only in this isolated transport fixture.
        assertEquals(1, jdbc.update("DELETE FROM outbox_events WHERE aggregate_id=? AND aggregate_version=3", auction.id()));
        var viewer = connect(auction.id(), bidderToken, "v1:" + auction.id() + ":2");
        var recovered = nextFrame(viewer);
        assertSnapshot(recovered, auction.id(), last.auctionVersion(), 120);
        var next = auctions.bid(bidder.id(), auction.id(), "after-gap", 130);
        var live = nextFrame(viewer);
        assertEquals("auction", live.type());
        assertEquals("v1:" + auction.id() + ":" + next.auctionVersion(), live.id());
        var payload = json.readTree(live.data()).get("payload");
        assertEquals(130, payload.get("highestBidAmountMinor").asLong());
        assertEquals(next.auctionVersion(), payload.get("version").asLong());
    }

    @Test void aCursorOutsideTheConfiguredReplayWindowGetsCurrentStateWithoutReplayingItsOldHistory() throws Exception {
        var auction = opened("Bounded retained replay");
        // The application under test has a 10,000-version replay window; these are transport fixtures, not bid-throughput evidence.
        int historical = 10001;
        seedTransportReplay(auction, historical);
        long currentVersion = historical + 2L;
        long currentAmount = 100 + (historical - 1L) * 10;
        var viewer = connect(auction.id(), bidderToken, "v1:" + auction.id() + ":2");
        assertSnapshot(nextFrame(viewer), auction.id(), currentVersion, currentAmount);
        var next = auctions.bid(bidder.id(), auction.id(), "after-window-recovery", currentAmount + 10);
        var live = nextFrame(viewer);
        assertEquals("auction", live.type());
        assertEquals("v1:" + auction.id() + ":" + next.auctionVersion(), live.id());
        assertEquals(currentVersion + 1, next.auctionVersion());
        assertEquals(currentAmount + 10, json.readTree(live.data()).get("payload").get("highestBidAmountMinor").asLong());
    }

    private Frame nextFrame(Connection connection) throws InterruptedException {
        var frame = connection.frames.poll(5, TimeUnit.SECONDS);
        assertNotNull(frame, "expected an SSE data frame");
        return frame;
    }

    private void assertSnapshot(Frame frame, UUID auction, long version, long amount) {
        assertEquals("snapshot", frame.type(), "gap recovery must not silently skip to a later event");
        var snapshot = json.readTree(frame.data());
        String cursor = "v1:" + auction + ":" + version;
        assertEquals(cursor, frame.id());
        assertEquals(cursor, snapshot.get("cursor").asString());
        assertEquals(auction.toString(), snapshot.get("auction").get("id").asString());
        assertEquals(version, snapshot.get("auction").get("version").asLong());
        assertEquals(amount, snapshot.get("auction").get("highestBidAmountMinor").asLong());
        assertNotNull(snapshot.get("serverTime"));
    }

    @Test void anonymousForeignAndCancelledStreamsCannotExposePrivateState() throws Exception {
        var draft=auctions.create(owner.id(),"Private fixture","Draft",100,10,Instant.now().plusSeconds(300));
        assertEquals(401,http.send(HttpRequest.newBuilder(uri(draft.id(),"")).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(404,http.send(request(draft.id(),bidderToken,""),HttpResponse.BodyHandlers.discarding()).statusCode());
        var opened=auctions.publish(owner.id(),draft.id());
        var viewer=connect(opened.id(),bidderToken,null);
        viewer.event("snapshot",5000);
        auctions.cancel(owner.id(),opened.id());
        viewer.ended.get(5,TimeUnit.SECONDS);
        assertTrue(viewer.frames.stream().noneMatch(frame->frame.data().contains("CANCELLED")));
        assertEquals(404,http.send(request(opened.id(),bidderToken,""),HttpResponse.BodyHandlers.discarding()).statusCode());
        var seller=connect(opened.id(),ownerToken,null);
        assertEquals("CANCELLED",json.readTree(seller.event("snapshot",5000).data()).get("auction").get("status").asString());
    }

    @Test void revocationDuringReplayStopsBeforeTheRestOfTheBatch() throws Exception {
        var auction=opened("r".repeat(3500));
        seedTransportReplay(auction,300);
        var viewer=connect(auction.id(),bidderToken,"v1:"+auction.id()+":2");
        viewer.event("auction",5000);
        auth.logout(bidderToken.refreshToken(),bidderToken.accessToken());
        viewer.ended.get(5,TimeUnit.SECONDS);
        long count=viewer.frames.stream().filter(frame->frame.type().equals("auction")).count()+1;
        assertTrue(count>0 && count<128,"revocation must interrupt the active replay batch; received "+count);
        assertEquals(401,http.send(request(auction.id(),bidderToken,""),HttpResponse.BodyHandlers.discarding()).statusCode());
    }

    @Test void accessExpiryDuringReplayStopsTheActiveBatch() throws Exception {
        var auction=opened("e".repeat(3500));
        seedTransportReplay(auction,300);
        var viewer=connect(auction.id(),bidderToken,"v1:"+auction.id()+":2");
        viewer.event("auction",5000);
        jdbc.update("UPDATE auth_access_tokens SET created_at=clock_timestamp()-interval '2 seconds',expires_at=clock_timestamp()-interval '1 second' WHERE token_hash=?",
                TokenCodec.digest(bidderToken.accessToken()));
        viewer.ended.get(5,TimeUnit.SECONDS);
        long count=viewer.frames.stream().filter(frame->frame.type().equals("auction")).count()+1;
        assertTrue(count>0 && count<128,"expiry must interrupt the active replay batch; received "+count);
        assertTrue(auth.authenticate(bidderToken.accessToken()).isEmpty());
    }

    @Test void futureOrForeignCursorIsRejectedBeforeOpeningAStream() throws Exception {
        var auction=opened("Cursor fixture");
        for (String cursor:List.of("v2:"+auction.id()+":2","v1:"+UUID.randomUUID()+":2","v1:"+auction.id()+":-1")) {
            var response=http.send(request(auction.id(),bidderToken,"?cursor="+URLEncoder.encode(cursor,StandardCharsets.UTF_8)),HttpResponse.BodyHandlers.discarding());
            assertEquals(400,response.statusCode());
        }
        assertEquals(409,http.send(request(auction.id(),bidderToken,"?cursor=v1:"+auction.id()+":99"),HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(0,streams.activeClients());
    }

    @Test void unreadRawSocketIsDisconnectedAndItsCapacityReturns() throws Exception {
        var auction=opened("s".repeat(3900));
        seedTransportReplay(auction,2000);
        long previous=streams.closedFor("overflow")+streams.closedFor("stall");
        var slow=new Socket();slow.setReceiveBufferSize(1024);slow.setSoTimeout(5000);
        slow.connect(new InetSocketAddress("127.0.0.1",port),3000);
        connections.add(slow);
        String header="GET /api/auctions/"+auction.id()+"/events?cursor=v1:"+auction.id()+":2 HTTP/1.1\r\n"
                +"Host: 127.0.0.1:"+port+"\r\nAuthorization: Bearer "+bidderToken.accessToken()+"\r\nConnection: keep-alive\r\n\r\n";
        slow.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));slow.getOutputStream().flush();
        // Read the response header only. Leave the event body unread to fill real TCP buffers.
        var received=new ByteArrayOutputStream();
        int value;
        while ((value=slow.getInputStream().read())!=-1) {
            received.write(value);
            if (received.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) break;
            assertTrue(received.size()<8192);
        }
        assertTrue(received.toString(StandardCharsets.US_ASCII).startsWith("HTTP/1.1 200"));
        await(()->streams.closedFor("overflow")+streams.closedFor("stall")>previous,15000);
        assertEquals(0,streams.activeClients(),"logical slow-client slot must be released");
        var healthy=connect(auction.id(),ownerToken,null);
        assertEquals(2002,json.readTree(healthy.event("snapshot",5000).data()).get("auction").get("version").asLong());
    }

    @Test void capacityIsFiniteAndRevocationMakesRoomForAnotherConnection() throws Exception {
        var auction=opened("Capacity fixture");
        connect(auction.id(),ownerToken,null).event("snapshot",5000);
        connect(auction.id(),bidderToken,null).event("snapshot",5000);
        assertEquals(503,http.send(request(auction.id(),ownerToken,""),HttpResponse.BodyHandlers.discarding()).statusCode());
        auth.logout(bidderToken.refreshToken(),bidderToken.accessToken());
        await(()->streams.activeClients()==1,5000);
        var replacement=connect(auction.id(),ownerToken,null);
        assertNotNull(replacement.event("snapshot",5000));
        assertEquals(2,streams.activeClients());
    }

    void seedTransportReplay(AuctionSnapshot auction,int count) {
        // Isolated transport fixture: seed committed outbox snapshots in PostgreSQL.
        // Auction/bid invariants are exercised by the separate transaction integration suite.
        jdbc.update("""
            INSERT INTO outbox_events(event_id,event_type,schema_version,aggregate_id,aggregate_version,occurred_at,payload)
            SELECT gen_random_uuid(),'bid.accepted',1,?,n+2,clock_timestamp(),
                payload || jsonb_build_object('version',n+2,'highestBidAmountMinor',100+(n-1)*10,
                    'highestBidderId',?::text)
            FROM outbox_events CROSS JOIN generate_series(1,?) n
            WHERE aggregate_id=? AND aggregate_version=2
            """,auction.id(),bidder.id(),count,auction.id());
        jdbc.update("UPDATE auctions SET version=?,highest_bid_amount=?,highest_bidder_id=? WHERE id=?",
                count+2,100+(count-1)*10,bidder.id(),auction.id());
    }
    static void await(BooleanSupplier condition,long timeoutMillis) throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(),"condition did not complete before deadline");
    }
    record Frame(String id,String type,String data) { }
    static final class Connection implements AutoCloseable {
        final InputStream input;
        final BlockingQueue<Frame> frames=new LinkedBlockingQueue<>();
        final CompletableFuture<Void> ended=new CompletableFuture<>();
        Connection(InputStream input) {
            this.input=input;
            Thread.ofVirtual().start(()->{
                try (var reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))) {
                    String id="",type="",data="",line;
                    while ((line=reader.readLine())!=null) {
                        if (line.isEmpty()) {
                            if (!type.isEmpty()) frames.add(new Frame(id,type,data));
                            id="";type="";data="";
                        } else if (line.startsWith("id: ")) id=line.substring(4);
                        else if (line.startsWith("event: ")) type=line.substring(7);
                        else if (line.startsWith("data: ")) data=line.substring(6);
                    }
                    ended.complete(null);
                } catch (IOException closed) { ended.complete(null); }
            });
        }
        Frame event(String type,long timeoutMillis) throws InterruptedException {
            long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (System.nanoTime()<deadline) {
                Frame next=frames.poll(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
                if (next!=null && next.type().equals(type)) return next;
            }
            fail("Missing SSE event: "+type);return null;
        }
        @Override public void close() throws IOException { input.close(); }
    }
}
