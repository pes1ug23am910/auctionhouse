package io.auctionhouse.cache;

import java.time.Duration;

public final class PageKvCacheStore extends TextProtocolCacheStore {
    public PageKvCacheStore(String host, int port, Duration timeout) {
        this(host, port, timeout, SocketCacheTransport.DEFAULT_MAX_VALUE_BYTES);
    }
    public PageKvCacheStore(String host, int port, Duration timeout, int maxValueBytes) {
        super(host, port, timeout, maxValueBytes);
    }
    public String backendName() { return "pagekv"; }
}
