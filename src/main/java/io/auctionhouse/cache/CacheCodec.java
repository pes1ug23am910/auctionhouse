package io.auctionhouse.cache;

public interface CacheCodec<T> {
    byte[] encode(T value) throws Exception;
    T decode(byte[] bytes) throws Exception;
}
