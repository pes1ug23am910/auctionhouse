package io.auctionhouse.auth;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalDemoControllerTest {
    @Test void validLocalPasswordIssuesCookiesAndDiscardsPreloginSession() {
        AuthService auth = mock(AuthService.class);
        AuctionPrincipal principal = new AuctionPrincipal(UUID.randomUUID(), "seller", "USER");
        when(auth.findOrCreateAccount("urn:auctionhouse:local-demo", "seller", "seller")).thenReturn(principal);
        when(auth.issue(principal)).thenReturn(new TokenPair("fixture-access", "fixture-refresh",
                Instant.now().plusSeconds(600), Instant.now().plusSeconds(1200)));
        var settings = new AuthSettings(null,null,null,true);
        var controller = new LocalDemoController(auth, new TokenCookies(settings),
                new BCryptPasswordEncoder(4).encode("disposable-test-password"));
        var request = new MockHttpServletRequest();
        MockHttpSession previous = (MockHttpSession) request.getSession();
        var response = new MockHttpServletResponse();
        assertEquals(principal, controller.login(new LocalDemoController.DemoLogin("seller","disposable-test-password"), request,response));
        assertTrue(previous.isInvalid());
        assertEquals(2,response.getHeaders("Set-Cookie").size());
        assertTrue(response.getHeaders("Set-Cookie").stream().allMatch(value -> value.contains("Secure") && value.contains("HttpOnly")));
    }

    @Test void wrongPasswordAndUnknownUsernameCannotIssueCredentials() {
        AuthService auth = mock(AuthService.class);
        var controller = new LocalDemoController(auth, new TokenCookies(new AuthSettings(null,null,null,true)),
                new BCryptPasswordEncoder(4).encode("disposable-test-password"));
        assertThrows(AuthFailure.class, () -> controller.login(new LocalDemoController.DemoLogin("seller","incorrect"),
                new MockHttpServletRequest(),new MockHttpServletResponse()));
        assertThrows(AuthFailure.class, () -> controller.login(new LocalDemoController.DemoLogin("arbitrary-owner-id","disposable-test-password"),
                new MockHttpServletRequest(),new MockHttpServletResponse()));
        verifyNoInteractions(auth);
    }

    @Test void unsetLocalPasswordDoesNotEnableAnyDefaultCredential() {
        AuthService auth = mock(AuthService.class);
        var controller = new LocalDemoController(auth, new TokenCookies(new AuthSettings(null,null,null,true)), "");
        assertThrows(ResponseStatusException.class, () -> controller.login(new LocalDemoController.DemoLogin("seller","password"),
                new MockHttpServletRequest(),new MockHttpServletResponse()));
        verifyNoInteractions(auth);
    }
}
