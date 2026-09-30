package io.auctionhouse.cache;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CacheExpiryTest {
    @Test void noExpiryIsExplicitAndPositiveFractionsRoundUp() {
        assertEquals(0, CacheExpiry.noExpiry().seconds());
        assertEquals(1, CacheExpiry.after(Duration.ofNanos(1)).seconds());
        assertEquals(2, CacheExpiry.after(Duration.ofMillis(1001)).seconds());
        assertEquals(2_592_000, CacheExpiry.after(Duration.ofDays(30)).seconds());
    }

    @Test void invalidDurationsCannotBecomePermanentOrAbsoluteTimestamps() {
        assertThrows(IllegalArgumentException.class, () -> CacheExpiry.after(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> CacheExpiry.after(Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class, () -> CacheExpiry.after(Duration.ofDays(30).plusNanos(1)));
        assertThrows(NullPointerException.class, () -> CacheExpiry.after(null));
    }
}
