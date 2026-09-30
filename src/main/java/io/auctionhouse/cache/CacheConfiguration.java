package io.auctionhouse.cache;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CacheSettings.class)
public class CacheConfiguration {
    @Bean
    CacheStore cacheStore(CacheSettings settings) {
        String backend = settings.backend().toLowerCase(Locale.ROOT);
        int port = settings.port() == 0 ? (backend.equals("redis") ? 6379 : 11211) : settings.port();
        return switch (backend) {
            case "disabled" -> new DisabledCacheStore();
            case "memcached" -> new MemcachedCacheStore(settings.host(), port, settings.timeout(), settings.maxValueBytes());
            case "redis" -> new RedisCacheStore(settings.host(), port, settings.timeout(), settings.maxValueBytes());
            case "pagekv" -> new PageKvCacheStore(settings.host(), port, settings.timeout(), settings.maxValueBytes());
            default -> throw new IllegalArgumentException("Unknown auctionhouse.cache.backend");
        };
    }

    @Bean
    CacheExpiry cacheExpiry(CacheSettings settings) {
        if (settings.backend().equalsIgnoreCase("pagekv") && !settings.noExpiry()) {
            throw new IllegalArgumentException("PageKV requires explicit no-expiry until its server expiry capability is verified");
        }
        return settings.noExpiry() ? CacheExpiry.noExpiry() : CacheExpiry.after(settings.ttl());
    }

    @Bean
    CacheAsideService cacheAsideService(CacheStore store, MeterRegistry registry) {
        return new CacheAsideService(store, registry);
    }
}
