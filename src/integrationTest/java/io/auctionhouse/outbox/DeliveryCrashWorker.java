package io.auctionhouse.outbox;

import io.auctionhouse.auction.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Disposable subprocess used only by the explicitly selected crash experiment. */
public final class DeliveryCrashWorker {
    public static void main(String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        String boundary=args[0];
        UUID actor=UUID.fromString(args[1]), auction=UUID.fromString(args[2]);
        var source=new PGSimpleDataSource();
        source.setURL(System.getenv("CRASH_DB_URL"));
        source.setUser(System.getenv("CRASH_DB_USER"));
        source.setPassword(System.getenv("CRASH_DB_PASSWORD"));
        var jdbc=new JdbcTemplate(source);
        var manager=new DataSourceTransactionManager(source);
        var events=new EventLog(jdbc) {
            @Override public void append(AuctionSnapshot value,String type,Instant at) {
                super.append(value,type,at);
                if (type.equals("bid.accepted")) crash(boundary,"BID_WRITE_BEFORE_COMMIT");
            }
        };
        var auctions=new AuctionService(jdbc,manager,events,AuctionService.Isolation.ROW_LOCKS,8);
        crash(boundary,"BEFORE_BID");
        auctions.bid(actor,auction,"crash-intent",150);
        crash(boundary,"BID_COMMIT_BEFORE_RESPONSE");
        if (boundary.equals("RECOVER_BID")) return;
        String topic=System.getenv("CRASH_TOPIC");
        var producerProperties=new Properties();
        producerProperties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,System.getenv("CRASH_BROKERS"));
        producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,StringSerializer.class.getName());
        producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,StringSerializer.class.getName());
        producerProperties.put(ProducerConfig.ACKS_CONFIG,"all");
        producerProperties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,"true");
        producerProperties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,"10000");
        producerProperties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,"5000");
        try (var producer=new KafkaProducer<String,String>(producerProperties)) {
            EventPublisher publisher=message -> {
                crash(boundary,"BEFORE_BROKER_SEND");
                try {
                    var metadata=producer.send(new ProducerRecord<>(topic,message.aggregateId().toString(),message.envelope())).get(15,TimeUnit.SECONDS);
                    crash(boundary,"BROKER_ACK_BEFORE_MARK");
                    return new EventPublisher.Receipt(metadata.partition(),metadata.offset());
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            };
            var relay=new OutboxRelay(jdbc,manager,publisher,16);
            relay.once();
            crash(boundary,"RELAY_COMMIT");
        }
        var consumerProperties=new Properties();
        consumerProperties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,System.getenv("CRASH_BROKERS"));
        consumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG,topic+"-sink");
        consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class.getName());
        consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class.getName());
        consumerProperties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,"false");
        consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");
        consumerProperties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,"1");
        consumerProperties.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG,"6000");
        consumerProperties.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG,"1000");
        var sink=new NotificationSink(jdbc,manager,new tools.jackson.databind.ObjectMapper());
        try (var consumer=new KafkaConsumer<String,String>(consumerProperties)) {
            consumer.subscribe(List.of(topic));
            long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
            while (System.nanoTime()<deadline) {
                for (var record:consumer.poll(Duration.ofMillis(250))) {
                    boolean bid=record.value().contains("bid.accepted");
                    if (bid) crash(boundary,"BEFORE_SINK_EFFECT");
                    new TransactionTemplate(manager).executeWithoutResult(status -> {
                        sink.accept(record.value());
                        if (bid) crash(boundary,"SINK_EFFECT_BEFORE_COMMIT");
                    });
                    if (bid) crash(boundary,"SINK_COMMIT_BEFORE_ACK");
                    consumer.commitSync();
                    if (bid) crash(boundary,"CONSUMER_ACK");
                }
                if (boundary.equals("RECOVER_ALL") && !consumer.assignment().isEmpty()
                        && jdbc.queryForObject("SELECT count(*) FROM notification_effects",Integer.class)==3) {
                    var ends=consumer.endOffsets(consumer.assignment());
                    boolean drained=true;
                    for (var entry:ends.entrySet()) if (consumer.position(entry.getKey())<entry.getValue()) drained=false;
                    if (drained) return;
                }
            }
        }
        throw new IllegalStateException("Expected boundary or reconciliation was not reached: "+boundary);
    }
    private static void crash(String selected,String current) {
        if (selected.equals(current)) {
            System.out.println("CRASH_BOUNDARY="+current);
            System.out.flush();
            Runtime.getRuntime().halt(73);
        }
    }
}
