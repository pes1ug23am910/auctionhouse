package io.auctionhouse.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import jakarta.servlet.http.Cookie;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TokenSecurityTest {
    @Test
    void tokenSecretsAreUnpredictableTypedAndOnlyDigestsArePersistable() {
        TokenCodec codec = new TokenCodec();
        Set<String> observed = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            String access = codec.accessToken();
            String refresh = codec.refreshToken();
            assertTrue(TokenCodec.isAccess(access));
            assertTrue(TokenCodec.isRefresh(refresh));
            assertFalse(TokenCodec.isRefresh(access));
            assertFalse(TokenCodec.isAccess(refresh));
            assertTrue(observed.add(access));
            assertTrue(observed.add(refresh));
            assertEquals(64, TokenCodec.digest(access).length());
            assertFalse(access.equals(TokenCodec.digest(access)), "stored digest must not contain the raw token");
        }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                TokenCodec.digest("abc"));
    }

    @Test
    void debugRepresentationsNeverExposeTokenSecrets() {
        var pair = new TokenPair("access-secret", "refresh-secret", Instant.EPOCH, Instant.EPOCH);
        assertFalse(pair.toString().contains("access-secret"));
        assertFalse(pair.toString().contains("refresh-secret"));
        assertFalse(new AuthController.RefreshRequest("refresh-secret").toString().contains("refresh-secret"));
    }

    @Test
    void malformedOrMixedCredentialsNeverFallBackToCookieAuthentication() {
        var request = new MockHttpServletRequest();
        assertNull(AccessTokenFilter.token(request));
        request.setCookies(new Cookie("AH_ACCESS", "cookie-token"));
        assertEquals("cookie-token", AccessTokenFilter.token(request));
        request.addHeader("Authorization", "Bearer bearer-token");
        assertThrows(AuthFailure.class, () -> AccessTokenFilter.token(request));
        var malformed = new MockHttpServletRequest();
        malformed.addHeader("Authorization", "Basic unsupported");
        assertThrows(AuthFailure.class, () -> AccessTokenFilter.token(malformed));
    }

    @Test
    void browserMutationsNeedCsrfWhileExplicitBearerDoesNotUseAmbientCookies() {
        var browser = new MockHttpServletRequest("POST", "/api/auctions");
        browser.setCookies(new Cookie("AH_ACCESS", "token"));
        assertTrue(SecurityConfiguration.requiresCsrf(browser));
        var bearer = new MockHttpServletRequest("POST", "/api/auctions");
        bearer.addHeader("Authorization", "Bearer token");
        assertFalse(SecurityConfiguration.requiresCsrf(bearer));
        bearer.setCookies(new Cookie("AH_ACCESS", "cookie"));
        assertTrue(SecurityConfiguration.requiresCsrf(bearer));
        assertTrue(SecurityConfiguration.requiresCsrf(new MockHttpServletRequest("POST", "/api/auth/demo/login")));
        assertFalse(SecurityConfiguration.requiresCsrf(new MockHttpServletRequest("GET", "/api/auctions")));
        assertFalse(SecurityConfiguration.requiresCsrf(new MockHttpServletRequest("POST", "/api/auctions")),
                "anonymous API mutations reach authentication and return401");
    }

    @Test
    void sessionLifetimesCannotBeZeroOrRemoveAbsoluteBound() {
        assertThrows(IllegalArgumentException.class,
                () -> new AuthSettings(Duration.ZERO, Duration.ofDays(7), Duration.ofDays(30), true));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthSettings(Duration.ofHours(2), Duration.ofDays(7), Duration.ofDays(30), true));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthSettings(Duration.ofMinutes(10), Duration.ofDays(40), Duration.ofDays(30), true));
    }
}
