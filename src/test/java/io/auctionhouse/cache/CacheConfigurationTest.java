package io.auctionhouse.cache;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class CacheConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(CacheConfiguration.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test void defaultsWorkWithoutAnyCacheServer() {
        context.run(c -> {
            assertNull(c.getStartupFailure());
            assertInstanceOf(DisabledCacheStore.class, c.getBean(CacheStore.class));
            assertEquals(30, c.getBean(CacheExpiry.class).seconds());
        });
    }

    @Test void pageKvUsesPositiveExpiryUnlessNoExpiryIsExplicit() {
        context.withPropertyValues("auctionhouse.cache.backend=pagekv").run(c -> {
            assertNull(c.getStartupFailure());
            assertInstanceOf(PageKvCacheStore.class, c.getBean(CacheStore.class));
            assertEquals(30, c.getBean(CacheExpiry.class).seconds());
        });
        context.withPropertyValues("auctionhouse.cache.backend=pagekv", "auctionhouse.cache.no-expiry=true").run(c -> {
            assertNull(c.getStartupFailure());
            assertEquals(0, c.getBean(CacheExpiry.class).seconds());
        });
    }

    @Test void unknownBackendsFailConfiguration() {
        context.withPropertyValues("auctionhouse.cache.backend=unrecognized").run(c -> assertNotNull(c.getStartupFailure()));
    }
}
