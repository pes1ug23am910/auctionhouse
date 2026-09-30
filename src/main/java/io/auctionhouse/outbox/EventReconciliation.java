package io.auctionhouse.outbox;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class EventReconciliation {
    public record Report(UUID cutId,long expected,List<UUID> missing,List<UUID> duplicateAttempts,List<UUID> unexpected) { }
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager manager;
    public EventReconciliation(JdbcTemplate jdbc,PlatformTransactionManager manager) { this.jdbc=jdbc;this.manager=manager; }
    public UUID capture() {
        var tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx.execute(status -> {
            UUID cut=UUID.randomUUID();
            jdbc.update("INSERT INTO event_cuts(cut_id) VALUES(?)",cut);
            jdbc.update("INSERT INTO event_cut_members(cut_id,event_id) SELECT ?,event_id FROM outbox_events",cut);
            return cut;
        });
    }
    public List<OutboxMessage> events(UUID cut,UUID after,int limit) {
        if (limit<1 || limit>1000) throw new io.auctionhouse.auction.AuctionException("INVALID_PAGE",400,"Limit must be 1..1000");
        requireCut(cut);
        return jdbc.query("SELECT " + OutboxMessage.PROJECTION + " FROM outbox_events WHERE event_id IN (SELECT event_id FROM event_cut_members WHERE cut_id=?) AND event_id>? ORDER BY event_id LIMIT ?",OutboxMessage.MAPPER,cut,after==null?new UUID(0,0):after,limit);
    }
    public List<String> notifications(UUID cut,UUID after,int limit) {
        if (limit<1 || limit>1000) throw new io.auctionhouse.auction.AuctionException("INVALID_PAGE",400,"Limit must be 1..1000");
        requireCut(cut);
        return jdbc.queryForList("SELECT n.payload::text FROM notification_effects n JOIN event_cut_members c USING(event_id) WHERE c.cut_id=? AND n.event_id>? ORDER BY n.event_id LIMIT ?",
                String.class,cut,after==null?new UUID(0,0):after,limit);
    }
    private void requireCut(UUID cut) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM event_cuts WHERE cut_id=?)",Boolean.class,cut)))
            throw new io.auctionhouse.auction.AuctionException("NOT_FOUND",404,"Source cut not found");
    }
    public Report report(UUID cut) {
        requireCut(cut);
        var tx=new TransactionTemplate(manager); tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx.execute(status -> new Report(cut,
            jdbc.queryForObject("SELECT count(*) FROM event_cut_members WHERE cut_id=?",Long.class,cut),
            jdbc.queryForList("SELECT event_id FROM event_cut_members c WHERE cut_id=? AND NOT EXISTS(SELECT 1 FROM notification_effects n WHERE n.event_id=c.event_id) ORDER BY event_id",UUID.class,cut),
            jdbc.queryForList("SELECT d.event_id FROM notification_deliveries d JOIN event_cut_members c USING(event_id) WHERE c.cut_id=? GROUP BY d.event_id HAVING count(*)>1 ORDER BY d.event_id",UUID.class,cut),
            jdbc.queryForList("SELECT event_id FROM notification_effects n WHERE NOT EXISTS(SELECT 1 FROM outbox_events e WHERE e.event_id=n.event_id) ORDER BY event_id",UUID.class)));
    }
}
