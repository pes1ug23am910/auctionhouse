package io.auctionhouse.outbox;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class EventQuarantine {
    private final JdbcTemplate jdbc;
    public EventQuarantine(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void store(ConsumerRecord<String, String> record) {
        String value = record.value();
        boolean truncated = value != null && value.length() > 1_000_000;
        jdbc.update("""
            INSERT INTO event_quarantine(topic,partition_id,record_offset,reason,payload,payload_truncated)
            VALUES(?,?,?,'INVALID_EVENT',?,?) ON CONFLICT DO NOTHING
            """, record.topic(), record.partition(), record.offset(),
            truncated ? value.substring(0, 1_000_000) : value, truncated);
    }
}
