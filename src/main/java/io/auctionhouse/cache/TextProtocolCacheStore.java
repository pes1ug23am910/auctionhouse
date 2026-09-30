package io.auctionhouse.cache;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

abstract class TextProtocolCacheStore implements CacheStore {
    private final SocketCacheTransport transport;
    private final boolean relativeExpiry;

    TextProtocolCacheStore(String host, int port, Duration timeout, int maxValueBytes, boolean relativeExpiry) {
        transport = new SocketCacheTransport(host, port, timeout, maxValueBytes);
        this.relativeExpiry = relativeExpiry;
    }

    public Optional<byte[]> get(String rawKey) {
        String key = SocketCacheTransport.key(rawKey);
        return transport.execute((in, out) -> {
            SocketCacheTransport.ascii(out, "get " + key + "\r\n");
            out.flush();
            String line = SocketCacheTransport.line(in);
            if (line.equals("END")) return Optional.empty();
            String[] fields = line.split(" ", -1);
            if (fields.length != 4 || !fields[0].equals("VALUE") || !fields[1].equals(key) || !fields[2].equals("0")) {
                throw new CacheException("Unexpected cache value header");
            }
            byte[] bytes = SocketCacheTransport.payload(in, transport.length(fields[3]));
            if (!SocketCacheTransport.line(in).equals("END")) throw new CacheException("Expected cache response terminator");
            return Optional.of(bytes);
        });
    }

    public void set(String rawKey, byte[] rawValue, CacheExpiry expiry) {
        String key = SocketCacheTransport.key(rawKey);
        byte[] value = transport.value(rawValue);
        Objects.requireNonNull(expiry, "expiry");
        if (!relativeExpiry && expiry.seconds() != 0) {
            throw new UnsupportedOperationException("This backend's currently declared protocol supports explicit no-expiry only");
        }
        transport.execute((in, out) -> {
            SocketCacheTransport.ascii(out, "set " + key + " 0 " + expiry.seconds() + " " + value.length + "\r\n");
            out.write(value);
            SocketCacheTransport.ascii(out, "\r\n");
            out.flush();
            if (!SocketCacheTransport.line(in).equals("STORED")) throw new CacheException("Cache rejected the value");
            return null;
        });
    }

    public void delete(String rawKey) {
        String key = SocketCacheTransport.key(rawKey);
        transport.execute((in, out) -> {
            SocketCacheTransport.ascii(out, "delete " + key + "\r\n");
            out.flush();
            String response = SocketCacheTransport.line(in);
            if (!response.equals("DELETED") && !response.equals("NOT_FOUND")) throw new CacheException("Unexpected cache deletion response");
            return null;
        });
    }
}
