package io.auctionhouse.auction;

import io.auctionhouse.auction.domain.*;
import io.auctionhouse.outbox.EventLog;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuctionService {
    public enum Isolation { ROW_LOCKS, SERIALIZABLE }
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final EventLog events;
    private final Isolation isolation;
    private final int maxAttempts;
    private final MeterRegistry metrics;
    private final ConcurrentMap<FlightKey, CompletableFuture<BidOutcome>> flights = new ConcurrentHashMap<>();
    private record FlightKey(UUID actor, UUID auction, String key, long amount) { }

    public AuctionService(JdbcTemplate jdbc, PlatformTransactionManager transactions, EventLog events,
            Isolation isolation, int maxAttempts) {
        this(jdbc, transactions, events, isolation, maxAttempts, new SimpleMeterRegistry());
    }

    @Autowired
    public AuctionService(JdbcTemplate jdbc, PlatformTransactionManager transactions, EventLog events,
            @Value("${auctionhouse.isolation:ROW_LOCKS}") Isolation isolation,
            @Value("${auctionhouse.transaction-attempts:8}") int maxAttempts, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.events = events;
        this.isolation = isolation;
        if (maxAttempts < 1 || maxAttempts > 20) throw new IllegalArgumentException("transaction-attempts must be 1..20");
        this.maxAttempts = maxAttempts;
        this.metrics = metrics;
    }

    public AuctionSnapshot create(UUID actor, String title, String description, long openingPrice,
            long increment, Instant endsAt) {
        if (actor == null || title == null || title.isBlank() || title.length() > 160 || description == null || description.length() > 4000)
            throw new AuctionException("INVALID_AUCTION", 400, "A title, description and authenticated owner are required");
        var domain = Auction.draft(UUID.randomUUID(), actor, openingPrice, increment, endsAt);
        return transaction(() -> {
            Instant now = databaseTime();
            if (!endsAt.isAfter(now)) throw new AuctionException("INVALID_DEADLINE", 400, "Closing time must be in the future");
            jdbc.update("""
                INSERT INTO auctions(id,owner_id,title,description,opening_price,minimum_increment,ends_at,status,version,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,'DRAFT',1,?,?)
                """, domain.id(), actor, title.trim(), description, openingPrice, increment, Timestamp.from(endsAt), Timestamp.from(now), Timestamp.from(now));
            var snapshot = load(domain.id(), false);
            events.append(snapshot, "auction.created", now);
            return snapshot;
        });
    }

    public AuctionSnapshot publish(UUID actor, UUID id) {
        return transaction(() -> {
            var before = owned(actor, load(id, true));
            if (before.status() == AuctionStatus.OPEN) return before;
            Instant now = databaseTime();
            Auction updated = transition(() -> before.domain().publish(now));
            jdbc.update("INSERT INTO listing_reservations(auction_id,reserved_at) VALUES(?,?)", id, Timestamp.from(now));
            jdbc.update("INSERT INTO announcements(auction_id,state) VALUES(?,'INACTIVE')", id);
            var after = save(before, updated, now);
            jdbc.update("UPDATE announcements SET state='ACTIVE',activated_at=? WHERE auction_id=?", Timestamp.from(now), id);
            events.append(after, "auction.published", now);
            return after;
        });
    }

    public AuctionSnapshot close(UUID actor, UUID id) {
        return transaction(() -> closeLocked(owned(actor, load(id, true))));
    }

    private AuctionSnapshot closeLocked(AuctionSnapshot before) {
        if (before.status() == AuctionStatus.CLOSED) return before;
        Instant now = databaseTime();
        var after = save(before, transition(() -> before.domain().close(now)), now);
        events.append(after, "auction.closed", now);
        return after;
    }

    public int closeDue() {
        var ids = jdbc.queryForList("SELECT id FROM auctions WHERE status='OPEN' AND ends_at<=clock_timestamp() ORDER BY ends_at,id LIMIT 100", UUID.class);
        int count = 0;
        for (UUID id : ids) {
            Boolean changed = transaction(() -> {
                var current = load(id, true);
                if (current.status() != AuctionStatus.OPEN || current.endsAt().isAfter(databaseTime())) return false;
                closeLocked(current);
                return true;
            });
            if (changed) count++;
        }
        return count;
    }

    public AuctionSnapshot cancel(UUID actor, UUID id) {
        return transaction(() -> {
            var before = owned(actor, load(id, true));
            if (before.status() == AuctionStatus.CANCELLED) return before;
            Instant now = databaseTime();
            var after = save(before, transition(() -> before.domain().cancel()), now);
            events.append(after, "auction.cancelled", now);
            return after;
        });
    }

    public BidOutcome bid(UUID actor, UUID id, String key, long amount) {
        validateKey(key);
        Objects.requireNonNull(actor, "authenticated actor");
        var flightKey = new FlightKey(actor, id, key, amount);
        var pending = new CompletableFuture<BidOutcome>();
        var existing = flights.putIfAbsent(flightKey, pending);
        if (existing != null) {
            try { return recordOutcome(existing.join()); }
            catch (CompletionException e) { if (e.getCause() instanceof RuntimeException cause) throw cause; throw e; }
        }
        try {
            var outcome = transaction(() -> bidLocked(actor, id, key, amount));
            pending.complete(outcome);
            return recordOutcome(outcome);
        } catch (RuntimeException | Error failure) {
            metrics.counter("auctionhouse.bid.response", "outcome", "error", "code",
                    failure instanceof AuctionException known ? known.code() : "INTERNAL").increment();
            pending.completeExceptionally(failure);
            throw failure;
        } finally { flights.remove(flightKey, pending); }
    }

    private BidOutcome recordOutcome(BidOutcome result) {
        metrics.counter("auctionhouse.bid.response", "outcome", result.accepted() ? "accepted" : "rejected",
                "code", result.accepted() ? "NONE" : result.rejection()).increment();
        return result;
    }

    private BidOutcome bidLocked(UUID actor, UUID id, String key, long amount) {
        // All mutations acquire the auction row before idempotency, bids and event rows.
        var before = load(id, true);
        var savedDigests = jdbc.queryForList("SELECT fingerprint FROM bid_intents WHERE actor_id=? AND auction_id=? AND operation='bid' AND intent_key=?", String.class, actor, id, key);
        if (!savedDigests.isEmpty()) {
            if (!fingerprint(amount).equals(savedDigests.getFirst()))
                throw new AuctionException("IDEMPOTENCY_CONFLICT", 409, "This key belongs to a different bid payload");
            return intent(actor, id, key).orElseThrow();
        }
        if ((before.status() == AuctionStatus.DRAFT || before.status() == AuctionStatus.CANCELLED) && !before.ownerId().equals(actor))
            throw new AuctionException("NOT_FOUND", 404, "Auction not found");
        Instant now = databaseTime();
        var decision = before.domain().placeBid(actor, amount, now);
        if (!decision.accepted() && isolation == Isolation.SERIALIZABLE) {
            // Serialize durable rejections too; row locks alone do not advance an SSI snapshot.
            jdbc.update("UPDATE auctions SET updated_at=updated_at WHERE id=?", id);
        }
        UUID bidId = decision.accepted() ? UUID.randomUUID() : null;
        var after = decision.accepted() ? save(before, decision.auction(), now) : before;
        if (decision.accepted()) {
            jdbc.update("INSERT INTO bids(id,auction_id,actor_id,amount,auction_version,accepted_at) VALUES(?,?,?,?,?,?)",
                    bidId, id, actor, amount, after.version(), Timestamp.from(now));
        }
        var result = new BidOutcome(actor, id, key, amount, decision.accepted(),
                decision.accepted() ? null : decision.rejection().name(), bidId, after.version(), now);
        jdbc.update("""
            INSERT INTO bid_intents(actor_id,auction_id,operation,intent_key,fingerprint,amount,accepted,rejection,bid_id,auction_version,decided_at,replay_until)
            VALUES(?,?,'bid',?,?,?,?,?,?,?,?,?)
            """, actor, id, key, fingerprint(amount), amount, result.accepted(), result.rejection(), bidId, result.auctionVersion(), Timestamp.from(now), Timestamp.from(now.plusSeconds(2_592_000)));
        if (decision.accepted()) events.append(after, "bid.accepted", now);
        return result;
    }

    public Optional<BidOutcome> intent(UUID actor, UUID id, String key) {
        validateKey(key);
        return jdbc.query("""
            SELECT *,replay_until<=clock_timestamp() AS expired FROM bid_intents WHERE actor_id=? AND auction_id=? AND operation='bid' AND intent_key=?
            """, (rs, row) -> {
                if (rs.getBoolean("expired")) throw new AuctionException("INTENT_EXPIRED",410,
                        "The replay window ended; this key remains reserved and must not be retried with a new key");
                return new BidOutcome(actor, id, key, rs.getLong("amount"), rs.getBoolean("accepted"),
                    rs.getString("rejection"), rs.getObject("bid_id", UUID.class), rs.getLong("auction_version"),
                    rs.getTimestamp("decided_at").toInstant());
            }, actor, id, key).stream().findFirst();
    }

    public record ReadStamp(long version, boolean cacheable) { }

    public ReadStamp readStamp(UUID actor, UUID id) {
        return jdbc.query("SELECT owner_id,status,version FROM auctions WHERE id=?", (rs,row) -> {
            var status=AuctionStatus.valueOf(rs.getString("status"));
            if ((status==AuctionStatus.DRAFT || status==AuctionStatus.CANCELLED) && !actor.equals(rs.getObject("owner_id",UUID.class)))
                throw new AuctionException("NOT_FOUND",404,"Auction not found");
            return new ReadStamp(rs.getLong("version"),status==AuctionStatus.OPEN || status==AuctionStatus.CLOSED);
        },id).stream().findFirst().orElseThrow(() -> new AuctionException("NOT_FOUND",404,"Auction not found"));
    }

    public AuctionSnapshot get(UUID actor, UUID id) {
        var snapshot = load(id, false);
        if ((snapshot.status() == AuctionStatus.DRAFT || snapshot.status() == AuctionStatus.CANCELLED) && !snapshot.ownerId().equals(actor))
            throw new AuctionException("NOT_FOUND", 404, "Auction not found");
        return snapshot;
    }

    public List<AuctionSnapshot> list(UUID actor, int offset, int limit, boolean mine) {
        if (offset < 0 || limit < 1 || limit > 100) throw new AuctionException("INVALID_PAGE", 400, "Use a nonnegative offset and a limit of 1..100");
        String filter = mine ? "owner_id=?" : "status IN ('OPEN','CLOSED')";
        return mine ? jdbc.query("SELECT * FROM auctions WHERE " + filter + " ORDER BY created_at DESC,id LIMIT ? OFFSET ?", MAPPER, actor, limit, offset)
                : jdbc.query("SELECT * FROM auctions WHERE " + filter + " ORDER BY created_at DESC,id LIMIT ? OFFSET ?", MAPPER, limit, offset);
    }

    public List<Map<String,Object>> bids(UUID actor, UUID id, long beforeVersion, int limit) {
        get(actor, id);
        if (limit < 1 || limit > 100) throw new AuctionException("INVALID_PAGE", 400, "Limit must be 1..100");
        return jdbc.query("SELECT id,actor_id,amount,auction_version,accepted_at FROM bids WHERE auction_id=? AND auction_version<? ORDER BY auction_version DESC LIMIT ?",
                (rs,row) -> Map.of("id",rs.getObject("id",UUID.class), "actorId",rs.getObject("actor_id",UUID.class), "amountMinor",rs.getLong("amount"), "auctionVersion",rs.getLong("auction_version"), "acceptedAt",rs.getTimestamp("accepted_at").toInstant()), id, beforeVersion, limit);
    }

    private AuctionSnapshot save(AuctionSnapshot before, Auction domain, Instant now) {
        jdbc.update("UPDATE auctions SET status=?,highest_bid_amount=?,highest_bidder_id=?,version=version+1,updated_at=? WHERE id=?",
                domain.status().name(), domain.highestBidAmount(), domain.highestBidderId(), Timestamp.from(now), before.id());
        return load(before.id(), false);
    }

    private AuctionSnapshot load(UUID id, boolean lock) {
        return jdbc.query("SELECT * FROM auctions WHERE id=?" + (lock ? " FOR UPDATE" : ""), MAPPER, id).stream().findFirst()
                .orElseThrow(() -> new AuctionException("NOT_FOUND", 404, "Auction not found"));
    }
    private AuctionSnapshot owned(UUID actor, AuctionSnapshot auction) {
        if (!auction.ownerId().equals(actor)) throw new AuctionException("FORBIDDEN", 403, "Only the owner can change this auction");
        return auction;
    }
    private Auction transition(Supplier<Auction> operation) {
        try { return operation.get(); }
        catch (IllegalArgumentException | IllegalStateException e) { throw new AuctionException("INVALID_TRANSITION", 409, e.getMessage()); }
    }
    private Instant databaseTime() {
        return jdbc.queryForObject("SELECT clock_timestamp()", (rs,row) -> rs.getTimestamp(1).toInstant());
    }
    private static void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw new AuctionException("INVALID_IDEMPOTENCY_KEY", 400, "Use 1..128 letters, digits, dots, underscores, colons or hyphens");
    }
    private static String fingerprint(long amount) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("bid:v1:amountMinor=" + amount).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private <T> T transaction(Supplier<T> operation) {
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            var tx = new TransactionTemplate(transactions);
            tx.setIsolationLevel(isolation == Isolation.SERIALIZABLE ? TransactionDefinition.ISOLATION_SERIALIZABLE : TransactionDefinition.ISOLATION_READ_COMMITTED);
            tx.setTimeout(15);
            try {
                return tx.execute(status -> {
                    jdbc.execute("SET LOCAL lock_timeout = '3s'");
                    jdbc.execute("SET LOCAL statement_timeout = '10s'");
                    return operation.get();
                });
            } catch (RuntimeException failure) {
                String state = retrySqlState(failure);
                if (state == null) throw failure;
                if (attempt + 1 == maxAttempts) {
                    metrics.counter("auctionhouse.transaction.exhausted", "isolation", isolation.name(), "sqlstate", state).increment();
                    throw new AuctionException("RETRY_EXHAUSTED", 503, "Temporary contention; retry using the same idempotency key");
                }
                metrics.counter("auctionhouse.transaction.retry", "isolation", isolation.name(), "sqlstate", state).increment();
                try { Thread.sleep(ThreadLocalRandom.current().nextLong(2, Math.min(250, 5L << Math.min(attempt, 6)) + 1)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AuctionException("INTERRUPTED", 503, "Request interrupted; reconcile the same intent before retrying"); }
            }
        }
        throw new IllegalStateException("unreachable");
    }
    private String retrySqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && Set.of("40001","40P01","55P03").contains(sql.getSQLState())) return sql.getSQLState();
        }
        return null;
    }
    private static final RowMapper<AuctionSnapshot> MAPPER = (rs,row) -> new AuctionSnapshot(
            rs.getObject("id",UUID.class), rs.getObject("owner_id",UUID.class), rs.getString("title"),rs.getString("description"),
            rs.getLong("opening_price"),rs.getLong("minimum_increment"),rs.getTimestamp("ends_at").toInstant(),
            AuctionStatus.valueOf(rs.getString("status")),rs.getObject("highest_bid_amount",Long.class),rs.getObject("highest_bidder_id",UUID.class),
            rs.getLong("version"),rs.getTimestamp("created_at").toInstant());
}
