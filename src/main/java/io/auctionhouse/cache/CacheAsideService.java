package io.auctionhouse.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.Predicate;

public final class CacheAsideService {
    private final CacheStore store;
    private final Counter hits, misses, loads, fallbacks, invalidations;

    public CacheAsideService(CacheStore store, MeterRegistry registry) {
        this.store = Objects.requireNonNull(store);
        Objects.requireNonNull(registry);
        hits = registry.counter("auctionhouse.cache.hit", "backend", store.backendName());
        misses = registry.counter("auctionhouse.cache.miss", "backend", store.backendName());
        loads = registry.counter("auctionhouse.cache.load", "backend", store.backendName());
        fallbacks = registry.counter("auctionhouse.cache.fallback", "backend", store.backendName());
        invalidations = registry.counter("auctionhouse.cache.invalidation", "backend", store.backendName());
    }

    public <T> T getOrLoad(Supplier<String> authoritativeVersionedKey, CacheCodec<T> codec,
                           Supplier<T> authoritativeLoader, CacheExpiry expiry) {
        return getOrLoad(authoritativeVersionedKey, codec, authoritativeLoader, expiry, value -> true);
    }

    public <T> T getOrLoad(Supplier<String> authoritativeVersionedKey, CacheCodec<T> codec,
                           Supplier<T> authoritativeLoader, CacheExpiry expiry, Predicate<T> cacheable) {
        Objects.requireNonNull(codec);
        Objects.requireNonNull(expiry);
        Objects.requireNonNull(cacheable);
        String key = Objects.requireNonNull(authoritativeVersionedKey.get(), "versioned cache key");
        boolean reachable = true;
        Optional<byte[]> cached;
        try {
            cached = store.get(key);
        } catch (Exception cacheFailure) {
            cached = Optional.empty();
            reachable = false;
            fallbacks.increment();
        }
        if (cached.isPresent()) {
            try {
                T value = Objects.requireNonNull(codec.decode(cached.get()), "decoded cache value");
                if (!cacheable.test(value)) throw new CacheException("Ineligible cached value");
                hits.increment();
                return value;
            } catch (Exception corruptValue) {
                fallbacks.increment();
                invalidate(key);
            }
        } else if (reachable) {
            misses.increment();
        }
        loads.increment();
        T value = authoritativeLoader.get();
        if (value != null && reachable && cacheable.test(value)) {
            try {
                store.set(key, codec.encode(value), expiry);
            } catch (Exception cacheFailure) {
                fallbacks.increment();
            }
        }
        return value;
    }

    public void invalidate(String versionedKey) {
        try {
            store.delete(versionedKey);
            invalidations.increment();
        } catch (Exception cacheFailure) {
            fallbacks.increment();
        }
    }
}
