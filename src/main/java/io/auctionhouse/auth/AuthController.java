package io.auctionhouse.auth;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final TokenCookies cookies;
    public AuthController(AuthService auth, TokenCookies cookies) { this.auth = auth; this.cookies = cookies; }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken csrf, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Map.of("token", csrf.getToken(), "headerName", csrf.getHeaderName());
    }
    @GetMapping("/session")
    public AuctionPrincipal session(@AuthenticationPrincipal AuctionPrincipal principal, HttpServletResponse response) {
        if (principal == null) { throw new AuthFailure("UNAUTHENTICATED"); }
        response.setHeader("Cache-Control", "no-store");
        return principal;
    }
    @PostMapping("/refresh") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void refresh(HttpServletRequest request, HttpServletResponse response) {
        cookies.write(response, auth.refresh(AccessTokenFilter.cookie(request, "AH_REFRESH")));
    }
    @PostMapping("/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        auth.logout(AccessTokenFilter.cookie(request, "AH_REFRESH"), AccessTokenFilter.token(request));
        cookies.clear(response);
        var session = request.getSession(false);
        if (session != null) { session.invalidate(); }
    }
    @PostMapping(path="/mobile/refresh", consumes="application/json")
    public TokenPair mobileRefresh(@Valid @RequestBody RefreshRequest body,
                                   HttpServletRequest request, HttpServletResponse response) {
        if (request.getCookies() != null && request.getCookies().length > 0) {
            throw new AuthFailure("AMBIENT_COOKIES_NOT_ALLOWED");
        }
        response.setHeader("Cache-Control", "no-store");
        return auth.refresh(body.refreshToken());
    }
    public record RefreshRequest(@NotBlank @Size(max=128) String refreshToken) {
        @Override public String toString() { return "RefreshRequest[secret=REDACTED]"; }
    }
}
