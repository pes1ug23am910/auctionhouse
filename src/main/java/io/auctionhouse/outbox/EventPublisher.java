package io.auctionhouse.outbox;

@FunctionalInterface
public interface EventPublisher {
    record Receipt(int partition, long offset) { }
    Receipt publish(OutboxMessage message);
}
