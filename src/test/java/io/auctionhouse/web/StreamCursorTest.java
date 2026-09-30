package io.auctionhouse.web;

import static org.junit.jupiter.api.Assertions.*;
import io.auctionhouse.auction.AuctionException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StreamCursorTest {
    @Test void cursorCannotBeUsedForAnotherAuctionOrGeneration() {
        UUID id = UUID.randomUUID();
        var cursor = new AuctionStreams.Cursor(id, 42);
        assertEquals(cursor, AuctionStreams.Cursor.parse(cursor.toString(),id));
        assertThrows(AuctionException.class, () -> AuctionStreams.Cursor.parse(cursor.toString(),UUID.randomUUID()));
        assertThrows(AuctionException.class, () -> AuctionStreams.Cursor.parse("v2:"+id+":42",id));
        assertThrows(AuctionException.class, () -> AuctionStreams.Cursor.parse("v1:"+id+":-1",id));
        assertThrows(AuctionException.class, () -> AuctionStreams.Cursor.parse("v1:"+id+":9223372036854775808",id));
    }
}
