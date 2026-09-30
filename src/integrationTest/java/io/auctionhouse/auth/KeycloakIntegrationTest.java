package io.auctionhouse.auth;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties={"auctionhouse.auth.secure-cookies=false", "auctionhouse.closure.enabled=false"})
class KeycloakIntegrationTest {
    static final int APP_PORT = availablePort();
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName
            .parse("postgres@sha256:5a5a84b19854a9ffaa54082c166ff4ec27473a361e496e5ea167f298f2da9722")
            .asCompatibleSubstituteFor("postgres"));
    @Container static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(DockerImageName.parse(
            "quay.io/keycloak/keycloak@sha256:3d911baa186f352563854039b95f21a7e2c01c76b527fdc64f24a0885b927bdf"))
            .withExposedPorts(8080)
            .withCommand("start-dev", "--import-realm")
            .withCopyToContainer(Transferable.of(realm(), 0644), "/opt/keycloak/data/import/auctionhouse-local-realm.json")
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withMemory(1024L*1024*1024))
            .waitingFor(Wait.forHttp("/realms/auctionhouse-local/.well-known/openid-configuration")
                    .forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));
    static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("server.port", () -> APP_PORT);
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.security.oauth2.client.registration.keycloak.client-id", () -> "auctionhouse-web");
        properties.add("spring.security.oauth2.client.registration.keycloak.client-authentication-method", () -> "none");
        properties.add("spring.security.oauth2.client.registration.keycloak.authorization-grant-type", () -> "authorization_code");
        properties.add("spring.security.oauth2.client.registration.keycloak.scope", () -> "openid,profile,email");
        properties.add("spring.security.oauth2.client.registration.keycloak.redirect-uri", () -> "{baseUrl}/login/oauth2/code/{registrationId}");
        properties.add("spring.security.oauth2.client.provider.keycloak.issuer-uri", KeycloakIntegrationTest::issuer);
    }

    @Test void actualKeycloakPublicClientSignsInThenApplicationExpiryRefreshAndLogoutAreEnforced() throws Exception {
        Browser browser = new Browser();
        var loginPage = browser.loginPage();
        assertEquals(200, loginPage.statusCode());
        URI submit = formAction(loginPage.body());
        var authentication = browser.form(submit, "seller", "local-seller-fixture-only");
        assertEquals(302, authentication.statusCode(), () -> safeProviderError(authentication.body()));
        URI callback = submit.resolve(authentication.headers().firstValue("Location").orElseThrow());
        assertTrue(callback.getPath().startsWith("/login/oauth2/code/keycloak"));
        var applicationLogin = browser.get(callback);
        assertEquals(302, applicationLogin.statusCode());
        assertEquals(URI.create(base()+"/"), URI.create(base()).resolve(applicationLogin.headers().firstValue("Location").orElseThrow()));
        var me = browser.get(URI.create(base()+"/api/auth/session"));
        assertEquals(200, me.statusCode());
        UUID account = UUID.fromString(JSON.readTree(me.body()).get("id").asText());
        assertEquals("USER", JSON.readTree(me.body()).get("role").asText());
        assertEquals(issuer(), jdbc.queryForObject("SELECT issuer FROM accounts WHERE id=?", String.class, account));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM spring_session_attributes WHERE attribute_name='SPRING_SECURITY_CONTEXT'", Integer.class));

        jdbc.update("""
                UPDATE auth_access_tokens SET created_at=clock_timestamp()-interval '2 minutes',
                expires_at=clock_timestamp()-interval '1 minute'
                WHERE family_id IN (SELECT id FROM auth_families WHERE account_id=?)
                """, account);
        assertEquals(401, browser.get(URI.create(base()+"/api/auth/session")).statusCode());
        var csrf = JSON.readTree(browser.get(URI.create(base()+"/api/auth/csrf")).body());
        var headers = Map.of(csrf.get("headerName").asText(), csrf.get("token").asText());
        assertEquals(204, browser.json("/api/auth/refresh", headers).statusCode());
        assertEquals(200, browser.get(URI.create(base()+"/api/auth/session")).statusCode());
        assertEquals(204, browser.json("/api/auth/logout", headers).statusCode());
        assertEquals(401, browser.get(URI.create(base()+"/api/auth/session")).statusCode());
        assertTrue(jdbc.queryForObject("SELECT bool_and(revoked_at IS NOT NULL) FROM auth_families WHERE account_id=?", Boolean.class, account));
    }

    @Test void wrongKeycloakPasswordDoesNotCreateApplicationSession() throws Exception {
        Browser browser = new Browser();
        var page = browser.loginPage();
        var denied = browser.form(formAction(page.body()), "bidder", "incorrect-disposable-password");
        assertEquals(200, denied.statusCode(), () -> safeProviderError(denied.body()));
        assertFalse(browser.cookies.getCookieStore().getCookies().stream().anyMatch(cookie -> cookie.getName().equals("AH_ACCESS")));
        assertEquals(401, browser.get(URI.create(base()+"/api/auth/session")).statusCode());
    }

    static String issuer() { return "http://localhost:"+KEYCLOAK.getMappedPort(8080)+"/realms/auctionhouse-local"; }
    static String base() { return "http://localhost:"+APP_PORT; }
    static int availablePort() {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
        catch (IOException failure) { throw new IllegalStateException("could not reserve test port", failure); }
    }
    static byte[] realm() {
        try {
            return Files.readString(Path.of("test-fixtures/keycloak/auctionhouse-local-realm.json"))
                    .replace("http://localhost:8080", base()).getBytes(StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("realm fixture unavailable", failure); }
    }
    static String safeProviderError(String html) {
        var message = Pattern.compile("(?s)<[^>]*class=\"[^\"]*kc-feedback-text[^\"]*\"[^>]*>(.*?)</[^>]+>").matcher(html);
        if (message.find()) {
            return "Provider error: " + message.group(1).replaceAll("<[^>]+>", " ")
                    .replaceAll("[A-Za-z0-9_-]{20,}", "[opaque]").trim();
        }
        if (html.contains("Cookie not found")) { return "Provider error: Cookie not found"; }
        if (html.contains("Invalid code")) { return "Provider error: Invalid code"; }
        if (html.contains("Invalid request")) { return "Provider error: Invalid request"; }
        String text = html.replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("<[^>]+>", " ").replaceAll("https?://[^\\s]+", "[url]")
                .replaceAll("[A-Za-z0-9._~-]{20,}", "[opaque]").replaceAll("\\s+", " ").trim();
        return "Provider message: " + text.substring(0, Math.min(300,text.length()));
    }    static URI formAction(String html) {
        var forms = Pattern.compile("<form\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
        while (forms.find()) {
            String form = forms.group();
            if (form.contains("kc-form-login")) {
                var action = Pattern.compile("action=\"([^\"]+)\"").matcher(form);
                if (action.find()) { return URI.create(action.group(1).replace("&amp;", "&")); }
            }
        }
        throw new AssertionError("Keycloak login form was not present");
    }
    /** Models the browser Secure-cookie exception for trustworthy localhost HTTP only. */
    static final class LoopbackBrowserCookies extends CookieManager {
        LoopbackBrowserCookies() { super(null, CookiePolicy.ACCEPT_ALL); }
        @Override public Map<String,java.util.List<String>> get(URI uri,
                Map<String,java.util.List<String>> headers) throws IOException {
            URI cookieOrigin = "http".equals(uri.getScheme()) && "localhost".equals(uri.getHost())
                    ? URI.create("https:" + uri.toString().substring(5)) : uri;
            return super.get(cookieOrigin,headers);
        }
    }    static final class Browser {
        final CookieManager cookies = new LoopbackBrowserCookies();
        final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> get(URI uri) throws Exception {
            return client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> loginPage() throws Exception {
            var start = get(URI.create(base()+"/oauth2/authorization/keycloak"));
            assertEquals(302,start.statusCode());
            URI location = URI.create(start.headers().firstValue("Location").orElseThrow());
            var page = get(location);
            for (int i=0; i<5 && page.statusCode()/100==3; i++) {
                location = location.resolve(page.headers().firstValue("Location").orElseThrow());
                page = get(location);
            }
            return page;
        }
        HttpResponse<String> form(URI uri, String username, String password) throws Exception {
            String body = "username="+URLEncoder.encode(username, StandardCharsets.UTF_8)
                    +"&password="+URLEncoder.encode(password, StandardCharsets.UTF_8)+"&credentialId=";
            return client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                    .header("Content-Type","application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> json(String path, Map<String,String> headers) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(base()+path)).timeout(Duration.ofSeconds(15))
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}"));
            headers.forEach(request::header);
            return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
        }
    }
}
