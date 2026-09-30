package io.auctionhouse.auth;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public final class AccessTokenFilter extends OncePerRequestFilter {
    private final AuthService auth;
    public AccessTokenFilter(AuthService auth) { this.auth = auth; }

    public static String token(HttpServletRequest request) {
        List<String> headers = Collections.list(request.getHeaders("Authorization"));
        if (headers.size() > 1) { throw new AuthFailure("AMBIGUOUS_CREDENTIALS"); }
        String cookie = cookie(request, "AH_ACCESS");
        if (headers.isEmpty()) { return cookie; }
        String header = headers.getFirst();
        if (cookie != null || !header.regionMatches(true, 0, "Bearer ", 0, 7)
                || header.length() <= 7 || header.substring(7).contains(" ")) {
            throw new AuthFailure("AMBIGUOUS_OR_INVALID_CREDENTIALS");
        }
        return header.substring(7);
    }

    public static String cookie(HttpServletRequest request, String name) {
        String found = null;
        if (request.getCookies() == null) { return null; }
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                if (found != null) { throw new AuthFailure("AMBIGUOUS_CREDENTIALS"); }
                found = cookie.getValue();
            }
        }
        return found;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath().isEmpty() ? request.getRequestURI() : request.getServletPath();
        return path.equals("/") || path.equals("/index.html") || path.startsWith("/assets/")
                || path.equals("/favicon.ico") || path.equals("/login") || path.equals("/error")
                || path.equals("/api/auth/csrf") || path.equals("/api/auth/refresh")
                || path.equals("/api/auth/logout") || path.equals("/api/auth/mobile/refresh")
                || path.equals("/api/auth/demo/login") || path.startsWith("/oauth2/")
                || path.startsWith("/login/oauth2/") || (path.equals("/actuator/health") || path.startsWith("/actuator/health/"));
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        try {
            String raw = token(request);
            if (raw != null) {
                AuctionPrincipal principal = auth.authenticate(raw).orElseThrow(() -> new AuthFailure("INVALID_ACCESS"));
                var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            }
        } catch (AuthFailure failure) {
            SecurityContextHolder.clearContext();
            unauthorized(response);
            return;
        }
        chain.doFilter(request, response);
    }

    static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"Authentication required\"}");
    }
}
