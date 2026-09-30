package io.auctionhouse.outbox;

import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "auctionhouse.broker.enabled", havingValue = "true")
public class BrokerConfiguration {
    @Bean NewTopic auctionEvents(@Value("${auctionhouse.broker.topic:auctionhouse.events.v1}") String name,
            @Value("${auctionhouse.broker.replicas:1}") int replicas) {
        return TopicBuilder.name(name).partitions(3).replicas(replicas)
                .config("min.insync.replicas", replicas > 1 ? "2" : "1")
                .config("retention.ms", "604800000").build();
    }

    @Bean CommonErrorHandler eventErrors() {
        var handler = new DefaultErrorHandler(new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        // Only the durable quarantine path may skip an invalid input. Infrastructure errors retry.
        handler.setClassifications(Map.of(), true);
        handler.setAckAfterHandle(false);
        return handler;
    }
}
