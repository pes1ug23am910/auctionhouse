package io.auctionhouse.auction.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable accepted state of a fixed-deadline, non-payment auction. */
public record Auction(
        UUID id,
        UUID ownerId,
        long openingPrice,
        long minimumIncrement,
        Instant endsAt,
        AuctionStatus status,
        Long highestBidAmount,
        UUID highestBidderId) {
    public static final long MAX_AMOUNT = 9_000_000_000_000_000L;

    public Auction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(endsAt, "endsAt");
        Objects.requireNonNull(status, "status");
        requireAmount(openingPrice, "openingPrice");
        requireAmount(minimumIncrement, "minimumIncrement");
        if ((highestBidAmount == null) != (highestBidderId == null)) {
            throw new IllegalArgumentException("highest bid amount and bidder must both be present or absent");
        }
        if (highestBidAmount != null) {
            requireAmount(highestBidAmount, "highestBidAmount");
            if (highestBidAmount < openingPrice) {
                throw new IllegalArgumentException("highest accepted bid must meet opening price");
            }
            if (ownerId.equals(highestBidderId)) {
                throw new IllegalArgumentException("owner cannot be the highest bidder");
            }
            if (status == AuctionStatus.DRAFT || status == AuctionStatus.CANCELLED) {
                throw new IllegalArgumentException("draft or cancelled auction cannot contain an accepted bid");
            }
        }
    }

    public static Auction draft(UUID id, UUID ownerId, long openingPrice,
                                long minimumIncrement, Instant endsAt) {
        return new Auction(id, ownerId, openingPrice, minimumIncrement, endsAt,
                AuctionStatus.DRAFT, null, null);
    }

    public Auction publish(Instant now) {
        Objects.requireNonNull(now, "now");
        requireStatus(AuctionStatus.DRAFT, "publish");
        if (!now.isBefore(endsAt)) {
            throw new IllegalStateException("cannot publish an auction at or after its deadline");
        }
        return withStatus(AuctionStatus.OPEN);
    }

    /**
     * Evaluates one attempt against an authoritative snapshot and server time.
     * Callers must serialize concurrent changes and persist accepted state atomically.
     */
    public BidDecision placeBid(UUID actorId, long amount, Instant now) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(now, "now");
        if (!validAmount(amount)) {
            return reject(BidRejection.INVALID_AMOUNT);
        }
        if (status != AuctionStatus.OPEN) {
            return reject(BidRejection.AUCTION_NOT_OPEN);
        }
        if (!now.isBefore(endsAt)) {
            return reject(BidRejection.AUCTION_ENDED);
        }
        if (ownerId.equals(actorId)) {
            return reject(BidRejection.OWNER_CANNOT_BID);
        }
        // Subtraction keeps the decision exact without constructing an out-of-range next price.
        if (amount < openingPrice
                || (highestBidAmount != null && amount - highestBidAmount < minimumIncrement)) {
            return reject(BidRejection.BID_TOO_LOW);
        }
        return new BidDecision(new Auction(id, ownerId, openingPrice, minimumIncrement,
                endsAt, status, amount, actorId), null);
    }

    public Auction close(Instant now) {
        Objects.requireNonNull(now, "now");
        requireStatus(AuctionStatus.OPEN, "close");
        if (now.isBefore(endsAt)) {
            throw new IllegalStateException("cannot close an auction before its deadline");
        }
        return withStatus(AuctionStatus.CLOSED);
    }

    public Auction cancel() {
        if (status != AuctionStatus.DRAFT && status != AuctionStatus.OPEN) {
            throw new IllegalStateException("only a draft or open auction can be cancelled");
        }
        if (highestBidAmount != null) {
            throw new IllegalStateException("cannot cancel an auction with an accepted bid");
        }
        return withStatus(AuctionStatus.CANCELLED);
    }

    public Optional<UUID> winnerId() {
        return status == AuctionStatus.CLOSED ? Optional.ofNullable(highestBidderId) : Optional.empty();
    }

    private Auction withStatus(AuctionStatus nextStatus) {
        return new Auction(id, ownerId, openingPrice, minimumIncrement, endsAt,
                nextStatus, highestBidAmount, highestBidderId);
    }

    private BidDecision reject(BidRejection rejection) {
        return new BidDecision(this, rejection);
    }

    private void requireStatus(AuctionStatus expected, String operation) {
        if (status != expected) {
            throw new IllegalStateException("cannot " + operation + " auction in state " + status);
        }
    }

    private static void requireAmount(long amount, String field) {
        if (!validAmount(amount)) {
            throw new IllegalArgumentException(field + " must be between 1 and " + MAX_AMOUNT + " minor units");
        }
    }

    private static boolean validAmount(long amount) {
        return amount > 0 && amount <= MAX_AMOUNT;
    }
}
