package io.auctionhouse.outbox;
import io.auctionhouse.observability.TraceContext;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
@Component
@ConditionalOnProperty(name="auctionhouse.broker.enabled",havingValue="true")
public class KafkaEventPublisher implements EventPublisher {
    private final KafkaTemplate<String,String> kafka;
    private final String topic;
    public KafkaEventPublisher(KafkaTemplate<String,String> kafka,@Value("${auctionhouse.broker.topic:auctionhouse.events.v1}") String topic) {
        this.kafka=kafka;this.topic=topic;
    }
    @Override public Receipt publish(OutboxMessage message) {
        try {
            var record=new ProducerRecord<>(topic,message.aggregateId().toString(),message.envelope());
            TraceContext.inject(record.headers(),(headers,key,value) -> {
                headers.remove(key);headers.add(key,value.getBytes(StandardCharsets.US_ASCII));
            });
            var metadata=kafka.send(record).get(3,TimeUnit.SECONDS).getRecordMetadata();
            return new Receipt(metadata.partition(),metadata.offset());
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Publication interrupted; outcome may be unknown",interrupted);
        } catch(java.util.concurrent.ExecutionException|java.util.concurrent.TimeoutException failure) {
            throw new IllegalStateException("Publication acknowledgement unavailable",failure);
        }
    }
}
