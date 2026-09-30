package io.auctionhouse.outbox;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="auctionhouse.broker.enabled",havingValue="true")
public class NotificationConsumer {
    private final NotificationSink sink;
    private final EventQuarantine quarantine;
    public NotificationConsumer(NotificationSink sink, EventQuarantine quarantine) {
        this.sink = sink;
        this.quarantine = quarantine;
    }
    @KafkaListener(topics="${auctionhouse.broker.topic:auctionhouse.events.v1}",groupId="${auctionhouse.broker.group:auctionhouse-notifications-v1}")
    public void receive(ConsumerRecord<String,String> record,Acknowledgment acknowledgement) {
        try { sink.accept(record.value()); }
        catch (IllegalArgumentException invalidEvent) { quarantine.store(record); }
        acknowledgement.acknowledge();
    }
}
