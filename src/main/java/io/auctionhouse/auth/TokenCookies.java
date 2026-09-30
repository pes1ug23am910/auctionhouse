package io.auctionhouse.auth;

import java.time.Duration;
import java.time.Instant;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public final class TokenCookies {
    private final AuthSettings settings;
    public TokenCookies(AuthSettings settings) { this.settings = settings; }

    public void write(HttpServletResponse response, TokenPair pair) {
        set(response, "AH_ACCESS", pair.accessToken(), "/", "Lax", remaining(pair.accessExpiresAt()));
        set(response, "AH_REFRESH", pair.refreshToken(), "/api/auth", "Strict", remaining(pair.refreshExpiresAt()));
        response.setHeader("Cache-Control", "no-store");
    }
    public void clear(HttpServletResponse response) {
        set(response, "AH_ACCESS", "", "/", "Lax", Duration.ZERO);
        set(response, "AH_REFRESH", "", "/api/auth", "Strict", Duration.ZERO);
        response.setHeader("Cache-Control", "no-store");
    }
    private void set(HttpServletResponse response, String name, String value, String path, String sameSite, Duration age) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(name, value)
                .httpOnly(true).secure(settings.secureCookies()).sameSite(sameSite).path(path).maxAge(age).build().toString());
    }
    private static Duration remaining(Instant expiry) {
        Duration duration = Duration.between(Instant.now(), expiry);
        return duration.isNegative() ? Duration.ZERO : duration;
    }
}
