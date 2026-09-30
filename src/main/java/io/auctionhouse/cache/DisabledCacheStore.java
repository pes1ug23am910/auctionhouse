package io.auctionhouse.cache;

import java.util.Optional;

public final class DisabledCacheStore implements CacheStore {
    public Optional<byte[]> get(String key) { return Optional.empty(); }
    public void set(String key, byte[] value, CacheExpiry expiry) { }
    public void delete(String key) { }
    public String backendName() { return "disabled"; }
}
