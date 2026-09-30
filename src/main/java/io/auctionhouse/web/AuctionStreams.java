package io.auctionhouse.web;

import io.auctionhouse.auction.*;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class AuctionStreams {
    public record Snapshot(AuctionSnapshot auction, String cursor, Instant serverTime) { }
    public record Cursor(UUID auctionId, long version) {
        @Override public String toString() { return "v1:" + auctionId + ":" + version; }
        public static Cursor parse(String text, UUID expected) {
            if (text == null || text.isBlank()) return new Cursor(expected, 0);
            String[] parts = text.split(":", -1);
            try {
                if (parts.length != 3 || !parts[0].equals("v1")
                        || !parts[1].equals(expected.toString()) || !parts[2].matches("0|[1-9][0-9]*"))
                    throw new IllegalArgumentException();
                return new Cursor(expected, Long.parseLong(parts[2]));
            } catch (IllegalArgumentException failure) {
                throw new AuctionException("INVALID_CURSOR", 400, "Cursor belongs to another stream or generation");
            }
        }
    }
    private record Event(long version, String payload) { }
    private final AuctionService auctions;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final int replayWindow, maxFrames, maxBytes;
    private final long stallNanos, lifetimeNanos;
    private final Semaphore capacity;
    private final ConcurrentMap<UUID, Client> clients = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, LongAdder> closures = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("auction-stream-watchdog").factory());
    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
    private static final long AUTH_AGE_NANOS = TimeUnit.SECONDS.toNanos(1);

    public AuctionStreams(AuctionService auctions, JdbcTemplate jdbc, ObjectMapper json,
            @Value("${auctionhouse.stream.replay-window:1000}") int replayWindow,
            @Value("${auctionhouse.stream.max-clients:128}") int maxClients,
            @Value("${auctionhouse.stream.max-queued-frames:128}") int maxFrames,
            @Value("${auctionhouse.stream.max-queued-bytes:262144}") int maxBytes,
            @Value("${auctionhouse.stream.stall-timeout-ms:2000}") long stallMillis,
            @Value("${auctionhouse.stream.max-lifetime-ms:600000}") long lifetimeMillis) {
        if (replayWindow < 1 || maxClients < 1 || maxFrames < 1 || maxBytes < 1024
                || stallMillis < 100 || lifetimeMillis < 1000)
            throw new IllegalArgumentException("Stream bounds must be positive and finite");
        this.auctions = auctions; this.jdbc = jdbc; this.json = json;
        this.replayWindow = replayWindow; this.maxFrames = maxFrames; this.maxBytes = maxBytes;
        this.stallNanos = TimeUnit.MILLISECONDS.toNanos(stallMillis);
        this.lifetimeNanos = TimeUnit.MILLISECONDS.toNanos(lifetimeMillis);
        this.capacity = new Semaphore(maxClients);
        this.workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(maxClients),
                Thread.ofPlatform().daemon().name("auction-stream-poll-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        watchdog.scheduleAtFixedRate(this::tick, 100, 100, TimeUnit.MILLISECONDS);
    }

    public Snapshot snapshot(UUID actor, UUID auction) {
        var state = auctions.get(actor, auction);
        var serverTime = jdbc.queryForObject("SELECT clock_timestamp()", (rs,row) -> rs.getTimestamp(1).toInstant());
        return new Snapshot(state, new Cursor(auction, state.version()).toString(), serverTime);
    }

    public void open(UUID actor, UUID auction, String resume, BooleanSupplier authorized,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        var initial = snapshot(actor, auction);
        var cursor = Cursor.parse(resume, auction);
        if (cursor.version() > initial.auction().version())
            throw new AuctionException("CURSOR_AHEAD", 409, "Refresh the snapshot before reconnecting");
        if (!authorized.getAsBoolean())
            throw new AuctionException("UNAUTHENTICATED", 401, "Authentication required");
        if (!capacity.tryAcquire())
            throw new AuctionException("STREAM_CAPACITY", 503, "Stream capacity reached; reconnect with backoff");
        AsyncContext async = null;
        Client created = null;
        try {
            response.setStatus(200);
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Accel-Buffering", "no");
            response.setBufferSize(1024);
            async = request.startAsync(request, response);
            async.setTimeout(TimeUnit.NANOSECONDS.toMillis(lifetimeNanos));
            var client = new Client(actor, auction, cursor.version(), authorized, async, response.getOutputStream());
            created = client;
            clients.put(client.id, client);
            async.addListener(new AsyncListener() {
                @Override public void onComplete(AsyncEvent event) { client.close("completed"); }
                @Override public void onTimeout(AsyncEvent event) { client.close("lifetime"); }
                @Override public void onError(AsyncEvent event) { client.close("io-error"); }
                @Override public void onStartAsync(AsyncEvent event) { client.close("redispatch"); }
            });
            client.output.setWriteListener(client.writer);
            client.writer.offer(": connected\n\n", false);
            client.schedule(System.nanoTime());
        } catch (IOException | RuntimeException failure) {
            if (created != null) created.close("initialization");
            else {
                capacity.release();
                if (async != null) async.complete();
            }
            throw failure;
        }
    }

    private void tick() {
        long now = System.nanoTime();
        for (var client : clients.values()) {
            if (now - client.started >= lifetimeNanos) client.close("lifetime");
            else if (client.writer.stalled(now, stallNanos)) client.close("stall");
            else if (now - client.lastAuthorized >= AUTH_AGE_NANOS) client.close("auth-stale");
            else client.schedule(now);
        }
    }

    public int activeClients() { return clients.size(); }
    public long closedFor(String reason) { var value=closures.get(reason); return value==null ? 0 : value.sum(); }

    private final class Client {
        final UUID id = UUID.randomUUID(), actor, auction;
        final BooleanSupplier authorized;
        final AsyncContext async;
        final ServletOutputStream output;
        final NonblockingSseWriter writer;
        final AtomicBoolean stopped = new AtomicBoolean(), polling = new AtomicBoolean();
        final long started = System.nanoTime();
        volatile long lastAuthorized = started, nextPoll, position, lastHeartbeat;
        Client(UUID actor, UUID auction, long position, BooleanSupplier authorized,
                AsyncContext async, ServletOutputStream output) {
            this.actor=actor; this.auction=auction; this.position=position;
            this.authorized=authorized; this.async=async; this.output=output;
            this.writer=new NonblockingSseWriter(output,this::authorize,this::close,maxFrames,maxBytes);
        }
        boolean authorize() {
            if (stopped.get()) return false;
            // Recheck current visibility for every frame, including already-queued frames.
            auctions.get(actor, auction);
            if (!authorized.getAsBoolean() || stopped.get()) return false;
            lastAuthorized=System.nanoTime();
            return true;
        }
        void schedule(long now) {
            if (stopped.get() || now < nextPoll || !polling.compareAndSet(false,true)) return;
            nextPoll=now+POLL_NANOS;
            try { workers.execute(() -> { try { poll(); } finally { polling.set(false); } }); }
            catch (RejectedExecutionException full) { polling.set(false); close("worker-capacity"); }
        }
        void poll() {
            if (stopped.get()) return;
            try {
                if (!authorize()) { close("authorization"); return; }
                var current=snapshot(actor,auction);
                if (position==0 || current.auction().version()-position>replayWindow) {
                    if (!writer.offer(frame("snapshot",current.cursor(),json.writeValueAsString(current)),true)) return;
                    position=current.auction().version();
                }
                var events=jdbc.query("""
                    SELECT aggregate_version,jsonb_build_object(
                      'eventId',event_id,'eventType',event_type,'schemaVersion',schema_version,
                      'aggregateId',aggregate_id,'aggregateVersion',aggregate_version,
                      'occurredAt',occurred_at,'payload',payload)::text AS envelope
                    FROM outbox_events WHERE aggregate_id=? AND aggregate_version>?
                    ORDER BY aggregate_version LIMIT 128
                    """,(rs,row)->new Event(rs.getLong(1),rs.getString(2)),auction,position);
                for (var event:events) {
                    if (stopped.get()) return;
                    if (event.version()!=position+1) {
                        var recovered=snapshot(actor,auction);
                        if (!writer.offer(frame("snapshot",recovered.cursor(),json.writeValueAsString(recovered)),true)) return;
                        position=recovered.auction().version();
                        break;
                    }
                    if (!writer.offer(frame("auction",new Cursor(auction,event.version()).toString(),event.payload()),true)) return;
                    position=event.version();
                }
                long now=System.nanoTime();
                if (now-lastHeartbeat>TimeUnit.SECONDS.toNanos(10)) {
                    writer.offer(": heartbeat\n\n",false);
                    lastHeartbeat=now;
                }
            } catch (AuctionException denied) { close("permission"); }
            catch (RuntimeException failure) { close("source-error"); }
        }
        void close(String reason) {
            if (!stopped.compareAndSet(false,true)) return;
            writer.stop(reason);
            clients.remove(id,this);
            capacity.release();
            closures.computeIfAbsent(reason,ignored->new LongAdder()).increment();
            try { async.complete(); } catch (RuntimeException alreadyComplete) { /* Container already finished. */ }
        }
    }
    private static String frame(String event,String cursor,String payload) {
        return "id: "+cursor+"\nevent: "+event+"\ndata: "+payload+"\n\n";
    }
    @PreDestroy public void shutdown() {
        watchdog.shutdownNow();
        for (var client:clients.values()) client.close("shutdown");
        workers.shutdownNow();
    }
}
