package io.auctionhouse.auction.domain;

import java.util.Objects;

/** A bid's domain outcome; persistence supplies request identity and durable replay. */
public record BidDecision(Auction auction, BidRejection rejection) {
    public BidDecision {
        Objects.requireNonNull(auction, "auction");
    }

    public boolean accepted() {
        return rejection == null;
    }
}
