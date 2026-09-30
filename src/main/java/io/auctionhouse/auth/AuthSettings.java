package io.auctionhouse.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("auctionhouse.auth")
public record AuthSettings(Duration accessLifetime, Duration refreshLifetime,
                           Duration familyLifetime, Boolean secureCookies) {
    public AuthSettings {
        accessLifetime = accessLifetime == null ? Duration.ofMinutes(10) : accessLifetime;
        refreshLifetime = refreshLifetime == null ? Duration.ofDays(7) : refreshLifetime;
        familyLifetime = familyLifetime == null ? Duration.ofDays(30) : familyLifetime;
        secureCookies = secureCookies == null ? true : secureCookies;
        if (accessLifetime.isNegative() || accessLifetime.isZero()
                || accessLifetime.compareTo(Duration.ofHours(1)) > 0
                || refreshLifetime.compareTo(accessLifetime) < 0
                || familyLifetime.compareTo(refreshLifetime) < 0
                || familyLifetime.compareTo(Duration.ofDays(90)) > 0) {
            throw new IllegalArgumentException("invalid bounded authentication lifetimes");
        }
    }
}
