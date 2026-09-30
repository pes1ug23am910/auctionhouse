package io.auctionhouse.outbox;
import io.auctionhouse.observability.TraceContext;
import java.time.Duration;
import java.util.function.LongSupplier;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Component
@ConditionalOnProperty(name="auctionhouse.broker.enabled",havingValue="true")
public class OutboxRelay {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EventPublisher publisher;
    private final int batch;
    private final MeterRegistry metrics;
    private final LongSupplier nanoTime;
    private static final long ATTEMPT_BUDGET_NANOS = Duration.ofSeconds(20).toNanos();
    public OutboxRelay(JdbcTemplate jdbc,PlatformTransactionManager manager,EventPublisher publisher,int batch) {
        this(jdbc,manager,publisher,batch,new SimpleMeterRegistry());
    }
    @Autowired
    public OutboxRelay(JdbcTemplate jdbc,PlatformTransactionManager manager,EventPublisher publisher,
            @Value("${auctionhouse.broker.batch-size:16}") int batch,MeterRegistry metrics) {
        this(jdbc, manager, publisher, batch, metrics, System::nanoTime);
    }
    OutboxRelay(JdbcTemplate jdbc,PlatformTransactionManager manager,EventPublisher publisher,
            int batch,MeterRegistry metrics,LongSupplier nanoTime) {
        if(batch<1||batch>100) throw new IllegalArgumentException("Relay batch must be 1..100");
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.tx.setTimeout(60);
        this.publisher=publisher;this.batch=batch;this.metrics=metrics;this.nanoTime=nanoTime;
        metrics.gauge("auctionhouse.outbox.pending",this,relay -> relay.pendingCount());
    }
    private double pendingCount() {
        try {return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL",Long.class);}
        catch(RuntimeException unavailable) {return Double.NaN;}
    }
    @Scheduled(fixedDelayString="${auctionhouse.broker.relay-ms:500}")
    public void scheduled() {once();}
    public int once() {
        return tx.execute(status -> {
            long started = nanoTime.getAsLong();
            var messages=jdbc.query("SELECT "+OutboxMessage.PROJECTION+" FROM outbox_events WHERE published_at IS NULL AND next_attempt_at<=clock_timestamp() ORDER BY occurred_at,event_id LIMIT ? FOR UPDATE SKIP LOCKED",OutboxMessage.MAPPER,batch);
            int published=0;
            for(var message:messages) {
                if (nanoTime.getAsLong() - started >= ATTEMPT_BUDGET_NANOS) break;
                var span=TraceContext.tracer().spanBuilder("outbox.publish").setSpanKind(SpanKind.PRODUCER)
                        .setParent(TraceContext.restore(message.traceParent(),message.traceState()))
                        .setAttribute("messaging.system","kafka").setAttribute("messaging.message.id",message.eventId().toString()).startSpan();
                try(var scope=span.makeCurrent()) {
                    EventPublisher.Receipt receipt;
                    try {receipt=publisher.publish(message);}
                    catch(RuntimeException unavailable) {
                        span.setStatus(StatusCode.ERROR);
                        metrics.counter("auctionhouse.outbox.publish","outcome","unacknowledged").increment();
                        jdbc.update("UPDATE outbox_events SET attempts=attempts+1,next_attempt_at=clock_timestamp()+make_interval(secs=>LEAST(300,power(2,LEAST(attempts+1,8))::integer)) WHERE event_id=?",message.eventId());
                        continue;
                    }
                    jdbc.update("UPDATE outbox_events SET published_at=clock_timestamp(),attempts=attempts+1,broker_partition=?,broker_offset=? WHERE event_id=?",receipt.partition(),receipt.offset(),message.eventId());
                    metrics.counter("auctionhouse.outbox.publish","outcome","acknowledged").increment();
                    published++;
                } finally {span.end();}
            }
            return published;
        });
    }
}
