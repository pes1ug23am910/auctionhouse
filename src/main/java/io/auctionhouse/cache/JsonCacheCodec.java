package io.auctionhouse.cache;

import java.util.Objects;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

public final class JsonCacheCodec<T> implements CacheCodec<T> {
    private final ObjectMapper mapper;
    private final JavaType type;

    public JsonCacheCodec(ObjectMapper mapper, Class<T> type) {
        this(mapper, mapper.getTypeFactory().constructType(type));
    }

    public JsonCacheCodec(ObjectMapper mapper, JavaType type) {
        this.mapper = Objects.requireNonNull(mapper);
        this.type = Objects.requireNonNull(type);
    }

    public byte[] encode(T value) { return mapper.writeValueAsBytes(value); }
    public T decode(byte[] bytes) { return mapper.readValue(bytes, type); }
}
