package io.auctionhouse.auth;

import java.time.Instant;

public record TokenPair(String accessToken, String refreshToken,
                        Instant accessExpiresAt, Instant refreshExpiresAt) {
    @Override
    public String toString() { return "TokenPair[secrets=REDACTED]"; }
}
