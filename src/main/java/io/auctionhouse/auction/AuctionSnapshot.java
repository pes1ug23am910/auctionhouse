package io.auctionhouse.auction;

import io.auctionhouse.auction.domain.Auction;
import io.auctionhouse.auction.domain.AuctionStatus;
import java.time.Instant;
import java.util.UUID;

public record AuctionSnapshot(UUID id, UUID ownerId, String title, String description,
        long openingPriceMinor, long minimumIncrementMinor, Instant endsAt, AuctionStatus status,
        Long highestBidAmountMinor, UUID highestBidderId, long version, Instant createdAt) {
    public Auction domain() {
        return new Auction(id, ownerId, openingPriceMinor, minimumIncrementMinor, endsAt,
                status, highestBidAmountMinor, highestBidderId);
    }
    public UUID winnerId() { return status == AuctionStatus.CLOSED ? highestBidderId : null; }
}
