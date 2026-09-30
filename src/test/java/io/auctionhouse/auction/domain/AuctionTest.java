package io.auctionhouse.auction.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AuctionTest {
    private static final UUID AUCTION = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final Instant START = Instant.parse("2026-09-30T12:00:00Z");
    private static final Instant END = START.plusSeconds(60);

    @Test
    void publicationOpensDraftWithoutInventingBidOrWinner() {
        Auction draft = draft(100, 10);
        Auction open = draft.publish(START);
        assertEquals(AuctionStatus.DRAFT, draft.status());
        assertEquals(AuctionStatus.OPEN, open.status());
        assertNull(open.highestBidAmount());
        assertNull(open.highestBidderId());
        assertTrue(open.winnerId().isEmpty());
        assertEquals(END, open.endsAt());
    }

    @Test
    void bidsMustMeetOpeningPriceThenMinimumIncrementAndCannotTie() {
        Auction open = draft(100, 10).publish(START);
        assertRejected(open, ALICE, 99, START, BidRejection.BID_TOO_LOW);
        BidDecision first = open.placeBid(ALICE, 100, START);
        assertTrue(first.accepted());
        assertNull(first.rejection());
        Auction withBid = first.auction();
        assertEquals(100L, withBid.highestBidAmount());
        assertEquals(ALICE, withBid.highestBidderId());
        assertNull(open.highestBidAmount(), "acceptance must not mutate the prior snapshot");
        assertRejected(withBid, BOB, 100, START, BidRejection.BID_TOO_LOW);
        assertRejected(withBid, BOB, 109, START, BidRejection.BID_TOO_LOW);
        BidDecision next = withBid.placeBid(BOB, 110, START);
        assertTrue(next.accepted());
        assertEquals(110L, next.auction().highestBidAmount());
        assertEquals(BOB, next.auction().highestBidderId());
    }

    @Test
    void bidsCannotExtendFixedDeadlineAndEqualityIsTooLate() {
        Auction open = draft(100, 10).publish(START);
        BidDecision lastMoment = open.placeBid(ALICE, 100, END.minusNanos(1));
        assertTrue(lastMoment.accepted());
        assertEquals(END, lastMoment.auction().endsAt());
        assertRejected(lastMoment.auction(), BOB, 110, END, BidRejection.AUCTION_ENDED);
        assertRejected(lastMoment.auction(), BOB, 110, END.plusNanos(1), BidRejection.AUCTION_ENDED);
    }

    @Test
    void ownerCannotBidEvenWhenPriceAndTimeWouldOtherwisePermitIt() {
        assertRejected(draft(100, 10).publish(START), OWNER, 100, START,
                BidRejection.OWNER_CANNOT_BID);
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -1, 0, 9_000_000_000_000_001L, Long.MAX_VALUE})
    void invalidAmountsAreRejectedWithoutChangingAuction(long amount) {
        assertRejected(draft(100, 10).publish(START), ALICE, amount, START,
                BidRejection.INVALID_AMOUNT);
    }

    @Test
    void maximumAmountIsExactAndNoFurtherIncrementCanWrapOrBypassLimit() {
        Auction open = draft(Auction.MAX_AMOUNT, Auction.MAX_AMOUNT).publish(START);
        BidDecision first = open.placeBid(ALICE, Auction.MAX_AMOUNT, START);
        assertTrue(first.accepted());
        assertEquals(Auction.MAX_AMOUNT, first.auction().highestBidAmount());
        assertRejected(first.auction(), BOB, Auction.MAX_AMOUNT, START, BidRejection.BID_TOO_LOW);
        assertRejected(first.auction(), BOB, Long.MAX_VALUE, START, BidRejection.INVALID_AMOUNT);
    }

    @Test
    void winnerExistsOnlyAfterCloseAndCloseCannotHappenEarly() {
        Auction open = draft(100, 10).publish(START)
                .placeBid(ALICE, 100, START).auction()
                .placeBid(BOB, 120, END.minusSeconds(1)).auction();
        assertTrue(open.winnerId().isEmpty());
        assertThrows(IllegalStateException.class, () -> open.close(END.minusNanos(1)));
        Auction closed = open.close(END);
        assertEquals(AuctionStatus.CLOSED, closed.status());
        assertEquals(BOB, closed.winnerId().orElseThrow());
        assertEquals(120L, closed.highestBidAmount());
        assertRejected(closed, ALICE, 130, END, BidRejection.AUCTION_NOT_OPEN);
        assertThrows(IllegalStateException.class, () -> closed.publish(START));
        assertThrows(IllegalStateException.class, closed::cancel);
    }

    @Test
    void closingWithoutBidsProducesNoWinner() {
        Auction closed = draft(100, 10).publish(START).close(END.plusSeconds(1));
        assertEquals(AuctionStatus.CLOSED, closed.status());
        assertTrue(closed.winnerId().isEmpty());
        assertNull(closed.highestBidAmount());
    }

    @Test
    void cancellationIsAllowedOnlyBeforeAnyAcceptedBidAndIsTerminal() {
        Auction draft = draft(100, 10);
        assertEquals(AuctionStatus.CANCELLED, draft.cancel().status());
        Auction cancelled = draft.publish(START).cancel();
        assertEquals(AuctionStatus.CANCELLED, cancelled.status());
        assertTrue(cancelled.winnerId().isEmpty());
        assertRejected(cancelled, ALICE, 100, START, BidRejection.AUCTION_NOT_OPEN);
        assertThrows(IllegalStateException.class, () -> cancelled.publish(START));
        assertThrows(IllegalStateException.class, () -> cancelled.close(END));
        assertThrows(IllegalStateException.class, cancelled::cancel);
        Auction withBid = draft.publish(START).placeBid(ALICE, 100, START).auction();
        assertThrows(IllegalStateException.class, withBid::cancel);
    }

    @Test
    void invalidLifecycleTransitionsAreRejected() {
        Auction draft = draft(100, 10);
        assertRejected(draft, ALICE, 100, START, BidRejection.AUCTION_NOT_OPEN);
        assertThrows(IllegalStateException.class, () -> draft.close(END));
        assertThrows(IllegalStateException.class, () -> draft.publish(END));
        assertThrows(IllegalStateException.class, () -> draft.publish(END.plusNanos(1)));
        Auction open = draft.publish(START);
        assertThrows(IllegalStateException.class, () -> open.publish(START));
        Auction closed = open.close(END);
        assertThrows(IllegalStateException.class, () -> closed.close(END));
    }

    @Test
    void restoredSnapshotsCannotViolateBasicDomainInvariants() {
        assertThrows(IllegalArgumentException.class, () -> draft(0, 10));
        assertThrows(IllegalArgumentException.class, () -> draft(100, 0));
        assertThrows(IllegalArgumentException.class, () -> draft(Long.MAX_VALUE, 10));
        assertThrows(IllegalArgumentException.class, () -> draft(100, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> restored(AuctionStatus.OPEN, 100L, null));
        assertThrows(IllegalArgumentException.class, () -> restored(AuctionStatus.OPEN, null, ALICE));
        assertThrows(IllegalArgumentException.class, () -> restored(AuctionStatus.OPEN, 99L, ALICE));
        assertThrows(IllegalArgumentException.class, () -> restored(AuctionStatus.OPEN, 100L, OWNER));
        assertThrows(IllegalArgumentException.class, () -> restored(AuctionStatus.DRAFT, 100L, ALICE));
        assertThrows(IllegalArgumentException.class, () -> restored(AuctionStatus.CANCELLED, 100L, ALICE));
    }

    @Test
    void acceptedHistoryMatchesIndependentBigIntegerOracle() {
        long seed = 20260930L;
        Random random = new Random(seed);
        Auction actual = draft(100, 7).publish(START);
        List<ObservedBid> accepted = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            long offered = 1 + random.nextInt(20_000);
            UUID actor = (i % 2 == 0) ? ALICE : BOB;
            BigInteger threshold = accepted.stream().map(b -> BigInteger.valueOf(b.amount()))
                    .max(BigInteger::compareTo).map(n -> n.add(BigInteger.valueOf(7)))
                    .orElse(BigInteger.valueOf(100));
            boolean expectedAccepted = BigInteger.valueOf(offered).compareTo(threshold) >= 0;
            Auction previous = actual;
            BidDecision decision = actual.placeBid(actor, offered, START.plusNanos(i));
            assertEquals(expectedAccepted, decision.accepted(), "seed=" + seed + ", step=" + i);
            actual = decision.auction();
            if (expectedAccepted) {
                accepted.add(new ObservedBid(actor, offered));
            } else {
                assertEquals(previous, actual, "rejected attempts must preserve accepted history");
                assertEquals(BidRejection.BID_TOO_LOW, decision.rejection());
            }
            if (!accepted.isEmpty()) {
                ObservedBid maximum = accepted.stream()
                        .max(java.util.Comparator.comparingLong(ObservedBid::amount)).orElseThrow();
                assertEquals(maximum.amount(), actual.highestBidAmount());
                assertEquals(maximum.actor(), actual.highestBidderId());
            }
        }
        assertTrue(accepted.size() > 1, "fixture must exercise multiple accepted bids");
        ObservedBid maximum = accepted.stream()
                .max(java.util.Comparator.comparingLong(ObservedBid::amount)).orElseThrow();
        assertEquals(maximum.actor(), actual.close(END).winnerId().orElseThrow());
    }

    private static Auction draft(long opening, long increment) {
        return Auction.draft(AUCTION, OWNER, opening, increment, END);
    }

    private static Auction restored(AuctionStatus status, Long amount, UUID bidder) {
        return new Auction(AUCTION, OWNER, 100, 10, END, status, amount, bidder);
    }

    private static void assertRejected(Auction auction, UUID actor, long amount, Instant now,
                                       BidRejection rejection) {
        BidDecision decision = auction.placeBid(actor, amount, now);
        assertFalse(decision.accepted());
        assertEquals(rejection, decision.rejection());
        assertSame(auction, decision.auction(), "a rejected bid must return the original snapshot");
    }

    private record ObservedBid(UUID actor, long amount) { }
}
