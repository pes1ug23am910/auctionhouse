package io.auctionhouse.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("auctionhouse.cache")
public record CacheSettings(
        @DefaultValue("disabled") String backend,
        @DefaultValue("127.0.0.1") String host,
        @DefaultValue("0") int port,
        @DefaultValue("500ms") Duration timeout,
        @DefaultValue("262144") int maxValueBytes,
        @DefaultValue("30s") Duration ttl,
        @DefaultValue("false") boolean noExpiry) {
}
