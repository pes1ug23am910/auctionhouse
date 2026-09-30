package io.auctionhouse.cache;

import java.util.Optional;

public interface CacheStore {
    Optional<byte[]> get(String key);
    void set(String key, byte[] value, CacheExpiry expiry);
    void delete(String key);
    String backendName();
}
