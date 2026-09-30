package io.auctionhouse.outbox;
import io.auctionhouse.observability.TraceContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.StatusCode;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
@Component
public class NotificationSink {
    private static final Set<String> TYPES=Set.of("auction.created","auction.published","auction.closed","auction.cancelled","bid.accepted");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    public NotificationSink(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper json) {
        this(jdbc,manager,json,new SimpleMeterRegistry());
    }
    @Autowired
    public NotificationSink(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper json,MeterRegistry metrics) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.json=json;this.metrics=metrics;
    }
    public boolean accept(String envelope) {
        var span=TraceContext.tracer().spanBuilder("notification.apply").startSpan();
        try(var scope=span.makeCurrent()) {
            boolean changed=apply(envelope);
            metrics.counter("auctionhouse.notification.delivery","outcome",changed?"applied":"duplicate").increment();
            return changed;
        } catch(IllegalArgumentException invalid) {
            metrics.counter("auctionhouse.notification.delivery","outcome","invalid").increment();
            span.setStatus(StatusCode.ERROR);throw invalid;
        } catch(RuntimeException unavailable) {
            metrics.counter("auctionhouse.notification.delivery","outcome","failed").increment();
            span.setStatus(StatusCode.ERROR);throw unavailable;
        } finally {span.end();}
    }
    private boolean apply(String envelope) {
        if(envelope==null||envelope.length()>1_000_000) throw new IllegalArgumentException("Invalid envelope size");
        JsonNode event;
        try {event=json.readTree(envelope);}
        catch(tools.jackson.core.JacksonException invalid) {throw new IllegalArgumentException("Malformed event JSON",invalid);}
        if(event==null||!event.isObject()) throw new IllegalArgumentException("Expected an event object");
        for(String field:Set.of("eventId","aggregateId","eventType","occurredAt"))
            if(!event.path(field).isString()) throw new IllegalArgumentException("Event identity and type must be strings");
        long schema=integer(event.path("schemaVersion"));
        long version=integer(event.path("aggregateVersion"));
        String type=event.path("eventType").asString();
        if(schema!=1||version<1||!TYPES.contains(type)||!event.path("payload").isObject())
            throw new IllegalArgumentException("Unsupported event schema or type");
        var payload=event.path("payload");
        if(!payload.path("id").isString()||integer(payload.path("version"))!=version)
            throw new IllegalArgumentException("Payload identity/version does not match envelope");
        UUID id;
        try {
            id=UUID.fromString(event.path("eventId").asString());
            UUID aggregate=UUID.fromString(event.path("aggregateId").asString());
            if(!aggregate.equals(UUID.fromString(payload.path("id").asString())))
                throw new IllegalArgumentException("Payload aggregate identity mismatch");
            java.time.Instant.parse(event.path("occurredAt").asString());
        } catch(RuntimeException invalid) {throw new IllegalArgumentException("Invalid event identity or time",invalid);}
        return tx.execute(status -> {
            int changed=jdbc.update("INSERT INTO notification_effects(event_id,event_type,payload) VALUES(?,?,?::jsonb) ON CONFLICT(event_id) DO NOTHING",id,type,envelope);
            if(changed==0&&!Boolean.TRUE.equals(jdbc.queryForObject("SELECT payload=?::jsonb FROM notification_effects WHERE event_id=?",Boolean.class,envelope,id)))
                throw new IllegalArgumentException("Event identity was reused with different content");
            jdbc.update("INSERT INTO notification_deliveries(event_id,duplicate) VALUES(?,?)",id,changed==0);
            return changed==1;
        });
    }
    private static long integer(JsonNode value) {
        if(!value.isIntegralNumber()||!value.canConvertToLong())
            throw new IllegalArgumentException("Event versions must be exact 64-bit integers");
        return value.asLong();
    }
}
