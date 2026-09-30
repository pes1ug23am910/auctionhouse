package io.auctionhouse.auction;

import io.auctionhouse.cache.*;
import io.auctionhouse.auction.domain.AuctionStatus;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class AuctionBrowse {
    private final AuctionService auctions;
    private final CacheAsideService cache;
    private final CacheSettings settings;
    private final CacheExpiry expiry;
    private final CacheCodec<AuctionSnapshot> codec;
    public AuctionBrowse(AuctionService auctions, CacheAsideService cache, CacheSettings settings,
            CacheExpiry expiry, ObjectMapper json) {
        this.auctions=auctions; this.cache=cache; this.settings=settings; this.expiry=expiry;
        this.codec=new JsonCacheCodec<>(json,AuctionSnapshot.class);
    }
    public AuctionSnapshot get(UUID actor, UUID id) {
        if (settings.backend().equals("disabled")) return auctions.get(actor,id);
        var stamp=auctions.readStamp(actor,id);
        if (!stamp.cacheable()) return auctions.get(actor,id);
        String key="auction:v1:"+id+":"+stamp.version();
        return cache.getOrLoad(() -> key, codec, () -> auctions.get(actor,id), expiry,
                value -> id.equals(value.id()) && value.version() == stamp.version()
                        && (value.status() == AuctionStatus.OPEN || value.status() == AuctionStatus.CLOSED));
    }
}
