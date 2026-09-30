package io.auctionhouse.auction.domain;

public enum BidRejection {
    INVALID_AMOUNT,
    AUCTION_NOT_OPEN,
    AUCTION_ENDED,
    OWNER_CANNOT_BID,
    BID_TOO_LOW
}
