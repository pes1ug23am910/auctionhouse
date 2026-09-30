package io.auctionhouse.outbox;
import io.auctionhouse.auction.AuctionSnapshot;
import io.auctionhouse.observability.TraceContext;
import io.opentelemetry.api.trace.StatusCode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
@Component
public class EventLog {
    private final JdbcTemplate jdbc;
    public EventLog(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public void append(AuctionSnapshot snapshot,String type,Instant occurredAt) {
        var span=TraceContext.tracer().spanBuilder("outbox.record").setAttribute("auctionhouse.event.type",type).startSpan();
        try(var scope=span.makeCurrent()) {
            var context=TraceContext.capture();
            jdbc.update("""
                INSERT INTO outbox_events(event_id,event_type,schema_version,aggregate_id,aggregate_version,occurred_at,payload,trace_parent,trace_state)
                SELECT ?, ?, 1, id, version, ?, jsonb_build_object(
                    'id',id, 'ownerId',owner_id, 'title',title, 'description',description,
                    'openingPriceMinor',opening_price, 'minimumIncrementMinor',minimum_increment,
                    'endsAt',ends_at, 'status',status, 'highestBidAmountMinor',highest_bid_amount,
                    'highestBidderId',highest_bidder_id, 'version',version, 'createdAt',created_at,
                    'winnerId',CASE WHEN status='CLOSED' THEN highest_bidder_id ELSE NULL END),?,?
                FROM auctions WHERE id=? AND version=?
                """,UUID.randomUUID(),type,Timestamp.from(occurredAt),context.traceparent(),context.tracestate(),snapshot.id(),snapshot.version());
        } catch(RuntimeException failure) {span.setStatus(StatusCode.ERROR);throw failure;}
        finally {span.end();}
    }
}
