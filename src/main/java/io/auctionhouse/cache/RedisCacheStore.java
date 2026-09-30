package io.auctionhouse.cache;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public final class RedisCacheStore implements CacheStore {
    private final SocketCacheTransport transport;

    public RedisCacheStore(String host, int port, Duration timeout) {
        this(host, port, timeout, SocketCacheTransport.DEFAULT_MAX_VALUE_BYTES);
    }
    public RedisCacheStore(String host, int port, Duration timeout, int maxValueBytes) {
        transport = new SocketCacheTransport(host, port, timeout, maxValueBytes);
    }
    public String backendName() { return "redis"; }

    public Optional<byte[]> get(String rawKey) {
        byte[] key = SocketCacheTransport.key(rawKey).getBytes(StandardCharsets.US_ASCII);
        return transport.execute((in, out) -> {
            command(out, bytes("GET"), key);
            String header = SocketCacheTransport.line(in);
            if (header.equals("$-1")) return Optional.empty();
            if (!header.startsWith("$")) throw new CacheException("Unexpected Redis GET response");
            return Optional.of(SocketCacheTransport.payload(in, transport.length(header.substring(1))));
        });
    }

    public void set(String rawKey, byte[] rawValue, CacheExpiry expiry) {
        byte[] key = SocketCacheTransport.key(rawKey).getBytes(StandardCharsets.US_ASCII);
        byte[] value = transport.value(rawValue);
        Objects.requireNonNull(expiry, "expiry");
        transport.execute((in, out) -> {
            if (expiry.seconds() == 0) command(out, bytes("SET"), key, value);
            else command(out, bytes("SET"), key, value, bytes("EX"), bytes(Integer.toString(expiry.seconds())));
            if (!SocketCacheTransport.line(in).equals("+OK")) throw new CacheException("Redis rejected the value");
            return null;
        });
    }

    public void delete(String rawKey) {
        byte[] key = SocketCacheTransport.key(rawKey).getBytes(StandardCharsets.US_ASCII);
        transport.execute((in, out) -> {
            command(out, bytes("DEL"), key);
            String response = SocketCacheTransport.line(in);
            if (!response.equals(":0") && !response.equals(":1")) throw new CacheException("Unexpected Redis DEL response");
            return null;
        });
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.US_ASCII); }

    private static void command(OutputStream out, byte[]... fields) throws IOException {
        SocketCacheTransport.ascii(out, "*" + fields.length + "\r\n");
        for (byte[] field : fields) {
            SocketCacheTransport.ascii(out, "$" + field.length + "\r\n");
            out.write(field);
            SocketCacheTransport.ascii(out, "\r\n");
        }
        out.flush();
    }
}
