package io.auctionhouse.auth;

import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Profile("local")
@RestController
@RequestMapping("/api/auth/demo")
public class LocalDemoController {
    private final AuthService auth;
    private final TokenCookies cookies;
    private final String passwordHash;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();

    public LocalDemoController(AuthService auth, TokenCookies cookies,
                              @Value("${auctionhouse.auth.demo.password-hash:}") String passwordHash) {
        this.auth = auth;
        this.cookies = cookies;
        this.passwordHash = passwordHash;
    }

    @PostMapping("/login")
    public AuctionPrincipal login(@Valid @RequestBody DemoLogin body, HttpServletRequest request, HttpServletResponse response) {
        if (passwordHash.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Local demo credentials are not configured");
        }
        if (!Set.of("seller", "bidder", "other").contains(body.username())
                || !passwords.matches(body.password(), passwordHash)) {
            throw new AuthFailure("INVALID_DEMO_CREDENTIALS");
        }
        AuctionPrincipal account = auth.findOrCreateAccount("urn:auctionhouse:local-demo", body.username(), body.username());
        cookies.write(response, auth.issue(account));
        var temporarySession = request.getSession(false);
        if (temporarySession != null) { temporarySession.invalidate(); }
        return account;
    }

    public record DemoLogin(@NotBlank @Size(max=20) String username,
                            @NotBlank @Size(max=72) String password) {
        @Override public String toString() { return "DemoLogin[credentials=REDACTED]"; }
    }
}
