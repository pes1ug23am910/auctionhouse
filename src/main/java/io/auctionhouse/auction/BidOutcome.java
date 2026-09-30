package io.auctionhouse.auction;

import java.time.Instant;
import java.util.UUID;

public record BidOutcome(UUID actorId, UUID auctionId, String key, long amountMinor,
        boolean accepted, String rejection, UUID bidId, long auctionVersion, Instant decidedAt) { }
