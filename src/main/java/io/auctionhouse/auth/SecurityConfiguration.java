package io.auctionhouse.auth;

import java.util.Arrays;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration
@EnableConfigurationProperties(AuthSettings.class)
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain security(HttpSecurity http, AuthService auth, TokenCookies cookies,
                                 ObjectProvider<ClientRegistrationRepository> registrations) throws Exception {
        http.securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .sessionManagement(session -> session.requireExplicitAuthenticationStrategy(true))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .csrf(csrf -> csrf.requireCsrfProtectionMatcher(SecurityConfiguration::requiresCsrf))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request,response,failure) -> AccessTokenFilter.unauthorized(response))
                        .accessDeniedHandler((request,response,failure) -> {
                            response.setStatus(403);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"Access denied\"}");
                        }))
                .authorizeHttpRequests(routes -> routes
                        .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico", "/error", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/auth/csrf", "/api/auth/refresh", "/api/auth/logout",
                                "/api/auth/mobile/refresh", "/api/auth/demo/login",
                                "/oauth2/authorization/**", "/login/oauth2/code/**", "/login").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(new AccessTokenFilter(auth), UsernamePasswordAuthenticationFilter.class);
        ClientRegistrationRepository clients = registrations.getIfAvailable();
        if (clients != null) {
            var resolver = new DefaultOAuth2AuthorizationRequestResolver(clients, "/oauth2/authorization");
            resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
            http.oauth2Login(login -> login
                    .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(resolver))
                    .successHandler((request,response,authentication) -> {
                        if (!(authentication.getPrincipal() instanceof OidcUser user)
                                || user.getIdToken().getIssuer() == null) {
                            AccessTokenFilter.unauthorized(response);
                            return;
                        }
                        var account = auth.findOrCreateAccount(user.getIdToken().getIssuer().toString(),
                                user.getSubject(), user.getFullName());
                        cookies.write(response, auth.issue(account));
                        var session = request.getSession(false);
                        if (session != null) { session.invalidate(); }
                        response.sendRedirect("/");
                    })
                    .failureHandler((request,response,failure) -> AccessTokenFilter.unauthorized(response)));
        }
        return http.build();
    }

    @Bean
    DefaultCookieSerializer sessionCookie(AuthSettings settings, Environment environment) {
        boolean development = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> profile.equals("local") || profile.equals("test"));
        if (!settings.secureCookies() && !development) {
            throw new IllegalStateException("insecure cookies are allowed only in local or test profiles");
        }
        var serializer = new DefaultCookieSerializer();
        serializer.setUseSecureCookie(settings.secureCookies());
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        serializer.setCookiePath("/");
        return serializer;
    }

    public static boolean requiresCsrf(HttpServletRequest request) {
        if (!CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)) { return false; }
        String path = request.getServletPath().isEmpty() ? request.getRequestURI() : request.getServletPath();
        if (path.equals("/api/auth/mobile/refresh")) { return false; }
        if (path.startsWith("/api/auth/")) { return true; }
        if (request.getCookies() == null) { return false; }
        return Arrays.stream(request.getCookies()).anyMatch(cookie ->
                cookie.getName().equals("AH_ACCESS") || cookie.getName().equals("AH_REFRESH")
                        || cookie.getName().equals("SESSION") || cookie.getName().equals("JSESSIONID"));
    }
}
