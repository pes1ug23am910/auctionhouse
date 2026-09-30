package io.auctionhouse.cache;

import java.time.Duration;

public final class MemcachedCacheStore extends TextProtocolCacheStore {
    public MemcachedCacheStore(String host, int port, Duration timeout) {
        this(host, port, timeout, SocketCacheTransport.DEFAULT_MAX_VALUE_BYTES);
    }
    public MemcachedCacheStore(String host, int port, Duration timeout, int maxValueBytes) {
        super(host, port, timeout, maxValueBytes, true);
    }
    public String backendName() { return "memcached"; }
}
