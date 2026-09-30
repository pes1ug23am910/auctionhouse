package io.auctionhouse.cache;

import java.time.Duration;
import java.util.Objects;

public sealed interface CacheExpiry permits CacheExpiry.NoExpiry, CacheExpiry.After {
    int seconds();

    static CacheExpiry noExpiry() { return NoExpiry.INSTANCE; }
    static CacheExpiry after(Duration duration) { return new After(duration); }

    enum NoExpiry implements CacheExpiry {
        INSTANCE;
        public int seconds() { return 0; }
    }

    record After(Duration duration) implements CacheExpiry {
        public After {
            Objects.requireNonNull(duration, "duration");
            if (duration.isNegative() || duration.isZero() || duration.compareTo(Duration.ofDays(30)) > 0) {
                throw new IllegalArgumentException("Relative cache expiry must be positive and at most 30 days");
            }
        }
        public int seconds() {
            return Math.toIntExact(duration.getSeconds() + (duration.getNano() == 0 ? 0 : 1));
        }
    }
}
