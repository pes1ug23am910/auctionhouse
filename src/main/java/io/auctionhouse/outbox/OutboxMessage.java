package io.auctionhouse.outbox;
import java.util.UUID;
public record OutboxMessage(UUID eventId,UUID aggregateId,long version,String envelope,String traceParent,String traceState) {
    public OutboxMessage(UUID eventId,UUID aggregateId,long version,String envelope) {
        this(eventId,aggregateId,version,envelope,null,null);
    }
    static final String PROJECTION="""
        event_id,aggregate_id,aggregate_version,trace_parent,trace_state,jsonb_build_object(
        'eventId',event_id,'eventType',event_type,'schemaVersion',schema_version,
        'aggregateId',aggregate_id,'aggregateVersion',aggregate_version,
        'occurredAt',occurred_at,'payload',payload)::text AS envelope
        """;
    static final org.springframework.jdbc.core.RowMapper<OutboxMessage> MAPPER=(rs,row) ->
        new OutboxMessage(rs.getObject("event_id",UUID.class),rs.getObject("aggregate_id",UUID.class),
            rs.getLong("aggregate_version"),rs.getString("envelope"),rs.getString("trace_parent"),rs.getString("trace_state"));
}
