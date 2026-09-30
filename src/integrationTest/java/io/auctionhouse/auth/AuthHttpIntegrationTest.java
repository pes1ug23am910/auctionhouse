package io.auctionhouse.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"auctionhouse.auth.secure-cookies=false", "auctionhouse.closure.enabled=false"})
@Import(AuthHttpIntegrationTest.ProbeConfiguration.class)
class AuthHttpIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            org.testcontainers.utility.DockerImageName.parse("postgres@sha256:5a5a84b19854a9ffaa54082c166ff4ec27473a361e496e5ea167f298f2da9722").asCompatibleSubstituteFor("postgres"));
    static final MockIssuer ISSUER = new MockIssuer();
    static final JsonMapper JSON = JsonMapper.builder().build();
    @Value("${local.server.port}") int port;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;
    @Autowired io.auctionhouse.outbox.NotificationSink notificationSink;
    AuctionPrincipal account;
    TokenPair pair;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.security.oauth2.client.registration.mock.client-id", () -> "auctionhouse-test");
        properties.add("spring.security.oauth2.client.registration.mock.client-secret", () -> "mock-issuer-fixture-only");
        properties.add("spring.security.oauth2.client.registration.mock.scope", () -> "openid,profile");
        properties.add("spring.security.oauth2.client.registration.mock.authorization-grant-type", () -> "authorization_code");
        properties.add("spring.security.oauth2.client.registration.mock.redirect-uri", () -> "{baseUrl}/login/oauth2/code/{registrationId}");
        properties.add("spring.security.oauth2.client.provider.mock.issuer-uri", ISSUER::issuer);
    }
    @BeforeEach void identity() {
        account = auth.findOrCreateAccount("https://http-fixture.test", UUID.randomUUID().toString(), "HTTP user");
        pair = auth.issue(account);
        ISSUER.mode = MockIssuer.Mode.VALID;
    }
    @AfterAll static void stopIssuer() { ISSUER.server.stop(0); }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void staleAccessCookieCannotBlockPublicAssetsOrRefreshRecovery(boolean revoked) throws Exception {
        if (revoked) {
            auth.logout(pair.refreshToken(), null);
        } else {
            jdbc.update("UPDATE auth_access_tokens SET created_at=clock_timestamp()-interval '20 minutes', "
                    + "expires_at=clock_timestamp()-interval '1 minute' WHERE family_id IN "
                    + "(SELECT id FROM auth_families WHERE account_id=?)", account.id());
        }
        Browser browser = new Browser();
        browser.cookie("AH_ACCESS", pair.accessToken(), "/");
        browser.cookie("AH_REFRESH", pair.refreshToken(), "/api/auth");
        for (String path : List.of("/", "/index.html", "/assets/auth-public-probe.js")) {
            var response = browser.get(path);
            assertEquals(200, response.statusCode(), "public asset with stale cookie: " + path);
            assertTrue(response.body().contains("public-auth-resource-probe"));
        }
        assertEquals(200, browser.get("/actuator/health/readiness").statusCode());
        assertEquals(401, browser.get("/api/auth/session").statusCode());
        JsonNode csrf = JSON.readTree(browser.get("/api/auth/csrf").body());
        var refreshed = browser.post("/api/auth/refresh", "{}",
                Map.of(csrf.get("headerName").asText(), csrf.get("token").asText()));
        assertEquals(revoked ? 401 : 204, refreshed.statusCode());
        assertEquals(revoked ? 401 : 200, browser.get("/api/auth/session").statusCode());
    }

    @Test void everyProtectedAuctionRouteRejectsAnonymousRequests() throws Exception {
        Browser browser = new Browser();
        String id = UUID.randomUUID().toString();
        for (String path : List.of("/api/auctions", "/api/auctions/bid-policy", "/api/auctions/"+id,
                "/api/auctions/"+id+"/bids", "/api/auctions/"+id+"/bid-intents/probe",
                "/api/auctions/"+id+"/snapshot", "/api/auctions/"+id+"/events",
                "/api/auth/session", "/api/admin/security-probe")) {
            assertEquals(401, browser.get(path).statusCode(), "anonymous GET " + path);
        }
        for (String path : List.of("/api/auctions", "/api/auctions/"+id+"/publish",
                "/api/auctions/"+id+"/close", "/api/auctions/"+id+"/cancel", "/api/auctions/"+id+"/bids")) {
            assertEquals(401, browser.post(path,"{}",Map.of()).statusCode(), "anonymous POST " + path);
        }
    }

    @Test void ownerAndOtherAccountRouteMatrixProtectsDraftsAndLifecycleChanges() throws Exception {
        Browser browser = new Browser();
        var ownerHeaders = Map.of("Authorization", "Bearer " + pair.accessToken());
        String create = JSON.writeValueAsString(Map.of("title", "Authorization fixture", "description", "",
                "openingPriceMinor", 100, "minimumIncrementMinor", 10,
                "endsAt", Instant.now().plusSeconds(180).toString()));
        var created = browser.post("/api/auctions", create, ownerHeaders);
        assertEquals(201, created.statusCode());
        String id = JSON.readTree(created.body()).get("id").asText();
        var other = auth.findOrCreateAccount("https://http-fixture.test", UUID.randomUUID().toString(), "Other actor");
        var otherPair = auth.issue(other);
        var otherHeaders = Map.of("Authorization", "Bearer " + otherPair.accessToken());
        assertEquals(200, browser.get("/api/auctions/"+id, ownerHeaders).statusCode());
        assertEquals(404, browser.get("/api/auctions/"+id, otherHeaders).statusCode());
        for (String action : List.of("publish", "close", "cancel")) {
            assertEquals(403, browser.post("/api/auctions/"+id+"/"+action, "{}", otherHeaders).statusCode());
        }
        jdbc.update("UPDATE accounts SET role='ADMIN' WHERE id=?", other.id());
        assertEquals(403, browser.post("/api/auctions/"+id+"/publish", "{}", otherHeaders).statusCode(),
                "administrator role is not a bypass for auction ownership");
        assertEquals(200, browser.post("/api/auctions/"+id+"/publish", "{}", ownerHeaders).statusCode());
        assertEquals(200, browser.get("/api/auctions/"+id, otherHeaders).statusCode());
    }
    @Test void amountJsonMustBeAnExactIntegerAndNeverCoercesStringsOrFractions() throws Exception {
        Browser browser=new Browser();
        var ownerHeaders=Map.of("Authorization","Bearer "+pair.accessToken());
        String create=JSON.writeValueAsString(Map.of("title","Strict amount fixture","description","",
                "openingPriceMinor",100,"minimumIncrementMinor",10,"endsAt",Instant.now().plusSeconds(180).toString()));
        var created=browser.post("/api/auctions",create,ownerHeaders);
        assertEquals(201,created.statusCode());
        String id=JSON.readTree(created.body()).get("id").asText();
        assertEquals(200,browser.post("/api/auctions/"+id+"/publish","{}",ownerHeaders).statusCode());
        var bidder=auth.findOrCreateAccount("https://strict-fixture.test",UUID.randomUUID().toString(),"Bidder");
        var bidderPair=auth.issue(bidder);
        var headers=Map.of("Authorization","Bearer "+bidderPair.accessToken(),"Idempotency-Key","strict-amount");
        for (String invalid:List.of("100.5","100.0","\"100\"","true","null","9000000000000001","{}")) {
            assertEquals(400,browser.post("/api/auctions/"+id+"/bids","{\"amountMinor\":"+invalid+"}",headers).statusCode(),invalid);
        }
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM bid_intents WHERE auction_id=?",Integer.class,UUID.fromString(id)));
        assertEquals(200,browser.post("/api/auctions/"+id+"/bids","{\"amountMinor\":100}",headers).statusCode());
    }

    @Test void expectedActorPreventsRebindingAnOfflineIntentAfterAccountSwitch() throws Exception {
        Browser browser = new Browser();
        var ownerHeaders = Map.of("Authorization", "Bearer " + pair.accessToken());
        String create = JSON.writeValueAsString(Map.of("title", "Account switch fixture", "description", "",
                "openingPriceMinor", 100, "minimumIncrementMinor", 10,
                "endsAt", Instant.now().plusSeconds(180).toString()));
        var created = browser.post("/api/auctions", create, ownerHeaders);
        assertEquals(201, created.statusCode());
        String id = JSON.readTree(created.body()).get("id").asString();
        assertEquals(200, browser.post("/api/auctions/" + id + "/publish", "{}", ownerHeaders).statusCode());
        var original = auth.findOrCreateAccount("https://actor-fixture.test", UUID.randomUUID().toString(), "Original");
        var switched = auth.findOrCreateAccount("https://actor-fixture.test", UUID.randomUUID().toString(), "Switched");
        var switchedPair = auth.issue(switched);
        var mismatched = Map.of("Authorization", "Bearer " + switchedPair.accessToken(),
                "Idempotency-Key", "offline-intent", "X-Expected-Actor", original.id().toString());
        String bidPath = "/api/auctions/" + id + "/bids";
        String intentPath = "/api/auctions/" + id + "/bid-intents/offline-intent";
        var rejected = browser.post(bidPath, "{\"amountMinor\":100}", mismatched);
        assertEquals(409, rejected.statusCode());
        assertEquals("ACTOR_CHANGED", JSON.readTree(rejected.body()).get("code").asString());
        assertEquals(409, browser.get(intentPath, mismatched).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM bid_intents WHERE auction_id=?", Integer.class, UUID.fromString(id)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM bids WHERE auction_id=?", Integer.class, UUID.fromString(id)));
        var malformed = Map.of("Authorization", "Bearer " + switchedPair.accessToken(),
                "Idempotency-Key", "offline-intent", "X-Expected-Actor", "not-a-uuid");
        assertEquals(400, browser.post(bidPath, "{\"amountMinor\":100}", malformed).statusCode());
        var originalHeaders = Map.of("Authorization", "Bearer " + auth.issue(original).accessToken(),
                "Idempotency-Key", "offline-intent", "X-Expected-Actor", original.id().toString());
        assertEquals(200, browser.post(bidPath, "{\"amountMinor\":100}", originalHeaders).statusCode());
        assertEquals(original.id().toString(), JSON.readTree(browser.get(intentPath, originalHeaders).body()).get("actorId").asString());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM bids WHERE auction_id=?", Integer.class, UUID.fromString(id)));
        assertEquals(404, browser.get(intentPath, Map.of("Authorization", "Bearer " + switchedPair.accessToken(),
                "X-Expected-Actor", switched.id().toString())).statusCode());
    }

    @Test void cancelledUnpublishedContentRemainsPrivateAcrossAllReadRoutes() throws Exception {
        Browser browser=new Browser();
        var ownerHeaders=Map.of("Authorization","Bearer "+pair.accessToken());
        String create=JSON.writeValueAsString(Map.of("title","Private draft","description","Private content",
                "openingPriceMinor",100,"minimumIncrementMinor",10,"endsAt",Instant.now().plusSeconds(180).toString()));
        var created=browser.post("/api/auctions",create,ownerHeaders);
        String id=JSON.readTree(created.body()).get("id").asText();
        assertEquals(200,browser.post("/api/auctions/"+id+"/cancel","{}",ownerHeaders).statusCode());
        var other=auth.findOrCreateAccount("https://strict-fixture.test",UUID.randomUUID().toString(),"Other");
        var otherHeaders=Map.of("Authorization","Bearer "+auth.issue(other).accessToken());
        for (String suffix:List.of("","/bids","/snapshot","/events"))
            assertEquals(404,browser.get("/api/auctions/"+id+suffix,otherHeaders).statusCode(),suffix);
        assertEquals(200,browser.get("/api/auctions/"+id,ownerHeaders).statusCode());
    }

    @Test void anonymousBearerAndRoleMatrixUsesLocalAuthority() throws Exception {
        Browser browser = new Browser();
        assertEquals(401, browser.get("/api/auth/session").statusCode());
        assertEquals(401, browser.post("/api/security-probe", "{}", Map.of()).statusCode());
        assertEquals(200, browser.get("/api/auth/session", Map.of("Authorization", "Bearer " + pair.accessToken())).statusCode());
        assertEquals(403, browser.get("/api/admin/security-probe", Map.of("Authorization", "Bearer " + pair.accessToken())).statusCode());
        jdbc.update("UPDATE accounts SET role='ADMIN' WHERE id=?", account.id());
        assertEquals(200, browser.get("/api/admin/security-probe", Map.of("Authorization", "Bearer " + pair.accessToken())).statusCode());
        auth.logout(pair.refreshToken(), null);
        assertEquals(401, browser.get("/api/auth/session", Map.of("Authorization", "Bearer " + pair.accessToken())).statusCode());
    }

    @Test void actualEventCutRoutesEnforceAdminAuthorityAndExportTheirCommittedMembership() throws Exception {
        Browser browser = new Browser();
        var userHeaders = Map.of("Authorization", "Bearer " + pair.accessToken());
        assertEquals(200, browser.get("/api/auctions/bid-policy", userHeaders).statusCode());
        String create = JSON.writeValueAsString(Map.of("title", "Admin export fixture", "description", "",
                "openingPriceMinor", 100, "minimumIncrementMinor", 10,
                "endsAt", Instant.now().plusSeconds(180).toString()));
        var auctionResponse = browser.post("/api/auctions", create, userHeaders);
        assertEquals(201, auctionResponse.statusCode());
        UUID auctionId = UUID.fromString(JSON.readTree(auctionResponse.body()).get("id").asString());
        String root = "/api/admin/event-cuts";
        long cutsBefore = jdbc.queryForObject("SELECT count(*) FROM event_cuts", Long.class);
        assertEquals(401, browser.post(root, "{}", Map.of()).statusCode());
        assertEquals(403, browser.post(root, "{}", userHeaders).statusCode());
        assertEquals(cutsBefore, jdbc.queryForObject("SELECT count(*) FROM event_cuts", Long.class),
                "rejected capture requests must not create any source-cut membership");

        var admin = auth.findOrCreateAccount("https://admin-fixture.test", UUID.randomUUID().toString(), "Cut administrator");
        jdbc.update("UPDATE accounts SET role='ADMIN' WHERE id=?", admin.id());
        var adminPair = auth.issue(admin);
        var adminHeaders = Map.of("Authorization", "Bearer " + adminPair.accessToken());
        var captured = browser.post(root, "{}", adminHeaders);
        assertEquals(200, captured.statusCode());
        UUID cut = UUID.fromString(JSON.readTree(captured.body()).get("cutId").asString());
        assertEquals(cutsBefore + 1, jdbc.queryForObject("SELECT count(*) FROM event_cuts", Long.class));
        String path = root + "/" + cut;
        for (String suffix : List.of("", "/events", "/notifications")) {
            assertEquals(401, browser.get(path + suffix).statusCode(), "anonymous " + suffix);
            assertEquals(403, browser.get(path + suffix, userHeaders).statusCode(), "USER " + suffix);
            assertEquals(200, browser.get(path + suffix, adminHeaders).statusCode(), "ADMIN " + suffix);
        }
        var expected = jdbc.queryForList(
                "SELECT event_id::text FROM event_cut_members WHERE cut_id=? ORDER BY event_id", String.class, cut);
        var exported = JSON.readTree(browser.get(path + "/events?limit=1000", adminHeaders).body());
        var exportedIds = new java.util.ArrayList<String>();
        JsonNode fixture = null;
        for (JsonNode event : exported) {
            exportedIds.add(event.get("eventId").asString());
            if (event.get("aggregateId").asString().equals(auctionId.toString())) fixture = event;
        }
        assertEquals(expected, exportedIds, "the real source endpoint exports the frozen membership in database UUID order");
        assertNotNull(fixture);
        assertTrue(notificationSink.accept(JSON.writeValueAsString(fixture)));
        String fixtureId = fixture.get("eventId").asString();
        var notifications = JSON.readTree(browser.get(path + "/notifications?limit=1000", adminHeaders).body());
        var observed = new java.util.ArrayList<String>();
        for (JsonNode event : notifications) observed.add(event.get("eventId").asString());
        assertTrue(observed.contains(fixtureId), "the actual sink export must include a committed local effect");
        assertEquals(jdbc.queryForList("SELECT n.event_id::text FROM notification_effects n "
                + "JOIN event_cut_members c USING(event_id) WHERE c.cut_id=? ORDER BY n.event_id", String.class, cut), observed);
        var report = JSON.readTree(browser.get(path, adminHeaders).body());
        assertEquals(cut.toString(), report.get("cutId").asString());
        assertEquals(expected.size(), report.get("expected").asInt());
        assertFalse(report.get("missing").toString().contains(fixtureId));
        assertEquals(0, report.get("unexpected").size());

        // Cookie-based administrative mutations retain CSRF protection, including their failure path.
        Browser cookieAdmin = new Browser();
        cookieAdmin.cookie("AH_ACCESS", adminPair.accessToken(), "/");
        assertEquals(403, cookieAdmin.post(root, "{}", Map.of()).statusCode());
        assertEquals(cutsBefore + 1, jdbc.queryForObject("SELECT count(*) FROM event_cuts", Long.class));
        var csrf = JSON.readTree(cookieAdmin.get("/api/auth/csrf").body());
        assertEquals(200, cookieAdmin.post(root, "{}",
                Map.of(csrf.get("headerName").asString(), csrf.get("token").asString())).statusCode());
        assertEquals(cutsBefore + 2, jdbc.queryForObject("SELECT count(*) FROM event_cuts", Long.class));

        jdbc.update("UPDATE accounts SET role='USER' WHERE id=?", admin.id());
        assertEquals(403, browser.post(root, "{}", adminHeaders).statusCode());
        for (String suffix : List.of("", "/events", "/notifications"))
            assertEquals(403, browser.get(path + suffix, adminHeaders).statusCode(), "revoked ADMIN role " + suffix);
        auth.logout(adminPair.refreshToken(), null);
        assertEquals(401, browser.get(path, adminHeaders).statusCode());
        assertEquals(cutsBefore + 2, jdbc.queryForObject("SELECT count(*) FROM event_cuts", Long.class));
    }

    @Test void cookieRefreshNeedsCsrfRotatesHttpOnlyCookiesAndLogoutRevokes() throws Exception {
        Browser browser = new Browser();
        browser.cookie("AH_ACCESS", pair.accessToken(), "/");
        browser.cookie("AH_REFRESH", pair.refreshToken(), "/api/auth");
        assertEquals(403, browser.post("/api/auth/refresh", "{}", Map.of()).statusCode());
        JsonNode csrf = JSON.readTree(browser.get("/api/auth/csrf").body());
        Map<String,String> headers = Map.of(csrf.get("headerName").asText(), csrf.get("token").asText());
        var refresh = browser.post("/api/auth/refresh", "{}", headers);
        assertEquals(204, refresh.statusCode());
        List<String> setCookies = refresh.headers().allValues("Set-Cookie");
        assertTrue(setCookies.stream().anyMatch(value -> value.startsWith("AH_ACCESS=") && value.contains("HttpOnly") && value.contains("SameSite=Lax")));
        assertTrue(setCookies.stream().anyMatch(value -> value.startsWith("AH_REFRESH=") && value.contains("HttpOnly") && value.contains("SameSite=Strict")));
        assertEquals(200, browser.get("/api/auth/session").statusCode());
        assertEquals(204, browser.post("/api/auth/logout", "{}", headers).statusCode());
        assertEquals(401, browser.get("/api/auth/session").statusCode());
        assertTrue(auth.authenticate(pair.accessToken()).isEmpty());
    }

    @Test void cookiesAndBearerCannotBeMixedAndMobileRefreshRejectsAmbientCookies() throws Exception {
        Browser browser = new Browser();
        browser.cookie("AH_ACCESS", pair.accessToken(), "/");
        assertEquals(401, browser.get("/api/auth/session", Map.of("Authorization", "Bearer " + pair.accessToken())).statusCode());
        String body = JSON.writeValueAsString(Map.of("refreshToken", pair.refreshToken()));
        assertEquals(401, browser.post("/api/auth/mobile/refresh", body, Map.of()).statusCode());
        Browser mobile = new Browser();
        assertEquals(200, mobile.post("/api/auth/mobile/refresh", body, Map.of()).statusCode());
    }

    @Test void nonLocalProfileExposesNoDemoPasswordLogin() throws Exception {
        Browser browser = new Browser();
        JsonNode csrf = JSON.readTree(browser.get("/api/auth/csrf").body());
        var result = browser.post("/api/auth/demo/login", "{\"username\":\"seller\",\"password\":\"unconfigured\"}",
                Map.of(csrf.get("headerName").asText(), csrf.get("token").asText()));
        assertEquals(404, result.statusCode());
    }

    @Test void actualOidcRedirectFlowValidatesPkceAndEndsTemporarySession() throws Exception {
        Browser browser = new Browser();
        var start = browser.get("/oauth2/authorization/mock");
        assertEquals(302, start.statusCode());
        String oldSession = browser.cookieValue("SESSION");
        URI authorization = URI.create(start.headers().firstValue("Location").orElseThrow());
        Map<String,String> query = MockIssuer.form(authorization.getRawQuery());
        assertNotNull(query.get("state"));
        assertNotNull(query.get("nonce"));
        assertEquals("S256", query.get("code_challenge_method"));
        assertNotNull(query.get("code_challenge"));
        var authorized = browser.get(authorization);
        URI callback = URI.create(authorized.headers().firstValue("Location").orElseThrow());
        var completed = browser.get(callback);
        assertEquals(302, completed.statusCode());
        assertEquals(URI.create(base()+"/"), URI.create(base()).resolve(completed.headers().firstValue("Location").orElseThrow()));
        assertEquals(200, browser.get("/api/auth/session").statusCode());
        assertTrue(ISSUER.pkceVerified);
        Browser stale = new Browser();
        stale.cookie("SESSION", oldSession, "/");
        assertEquals(401, stale.get("/api/auth/session").statusCode(),
                "temporary login session cannot authenticate without the short-lived access token");
    }

    @Test void callbackWithWrongStateCannotCreateApplicationTokens() throws Exception {
        Browser browser = new Browser();
        var start = browser.get("/oauth2/authorization/mock");
        var authorized = browser.get(URI.create(start.headers().firstValue("Location").orElseThrow()));
        URI callback = URI.create(authorized.headers().firstValue("Location").orElseThrow());
        Map<String,String> values = MockIssuer.form(callback.getRawQuery());
        URI tampered = URI.create(base() + "/login/oauth2/code/mock?code=" + values.get("code") + "&state=wrong-state");
        assertEquals(401, browser.get(tampered).statusCode());
        assertNull(browser.cookieValue("AH_ACCESS"));
    }

    @ParameterizedTest @EnumSource(value=MockIssuer.Mode.class, names={"WRONG_NONCE","EXPIRED","WRONG_ISSUER","WRONG_AUDIENCE","BAD_SIGNATURE"})
    void invalidIdTokensNeverIssueApplicationCredentials(MockIssuer.Mode mode) throws Exception {
        ISSUER.mode = mode;
        Browser browser = new Browser();
        var start = browser.get("/oauth2/authorization/mock");
        var authorized = browser.get(URI.create(start.headers().firstValue("Location").orElseThrow()));
        URI callback = URI.create(authorized.headers().firstValue("Location").orElseThrow());
        assertEquals(401, browser.get(callback).statusCode(), "invalid claim mode=" + mode);
        assertNull(browser.cookieValue("AH_ACCESS"));
    }

    String base() { return "http://localhost:" + port; }
    final class Browser {
        final CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient client = HttpClient.newBuilder().cookieHandler(cookieManager)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> get(String path) throws Exception { return get(path, Map.of()); }
        HttpResponse<String> get(String path, Map<String,String> headers) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(base()+path)).timeout(Duration.ofSeconds(10)).GET();
            headers.forEach(request::header);
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> get(URI uri) throws Exception {
            return client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> post(String path, String body, Map<String,String> headers) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(base()+path)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            headers.forEach(request::header);
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
        void cookie(String name, String value, String path) {
            var cookie = new HttpCookie(name, value);
            cookie.setVersion(0);
            cookie.setDomain("localhost.local");
            cookie.setPath(path);
            cookieManager.getCookieStore().add(URI.create(base()), cookie);
        }
        String cookieValue(String name) {
            return cookieManager.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals(name))
                    .map(HttpCookie::getValue).findFirst().orElse(null);
        }
    }

    @TestConfiguration static class ProbeConfiguration {
        @Bean ProbeController probeController() { return new ProbeController(); }
    }
    @RestController static class ProbeController {
        @GetMapping("/api/admin/security-probe") public Map<String,String> admin() { return Map.of("result","authorized"); }
    }

    static final class MockIssuer {
        enum Mode { VALID, WRONG_NONCE, EXPIRED, WRONG_ISSUER, WRONG_AUDIENCE, BAD_SIGNATURE }
        final HttpServer server;
        final RSAKey key;
        final RSAKey wrongKey;
        final Map<String,Map<String,String>> codes = new ConcurrentHashMap<>();
        volatile Mode mode = Mode.VALID;
        volatile boolean pkceVerified;
        MockIssuer() {
            try {
                var generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                var generated = generator.generateKeyPair();
                key = new RSAKey.Builder((RSAPublicKey) generated.getPublic())
                        .privateKey((RSAPrivateKey) generated.getPrivate()).keyID("mock-key").build();
                var different = generator.generateKeyPair();
                wrongKey = new RSAKey.Builder((RSAPublicKey) different.getPublic())
                        .privateKey((RSAPrivateKey) different.getPrivate()).keyID("mock-key").build();
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/.well-known/openid-configuration", exchange -> json(exchange, 200, Map.of(
                        "issuer", issuer(), "authorization_endpoint", issuer()+"/authorize", "token_endpoint", issuer()+"/token",
                        "jwks_uri", issuer()+"/jwks", "response_types_supported", List.of("code"),
                        "subject_types_supported", List.of("public"), "id_token_signing_alg_values_supported", List.of("RS256"),
                        "token_endpoint_auth_methods_supported", List.of("client_secret_basic"),
                        "code_challenge_methods_supported", List.of("S256"))));
                server.createContext("/jwks", exchange -> json(exchange, 200, new JWKSet(key.toPublicJWK()).toJSONObject()));
                server.createContext("/authorize", this::authorize);
                server.createContext("/token", this::token);
                server.start();
            } catch (Exception failure) { throw new IllegalStateException("cannot start mock identity provider", failure); }
        }
        String issuer() { return "http://127.0.0.1:"+server.getAddress().getPort(); }
        void authorize(HttpExchange exchange) throws IOException {
            Map<String,String> query = form(exchange.getRequestURI().getRawQuery());
            String code = UUID.randomUUID().toString();
            codes.put(code, query);
            String location = query.get("redirect_uri") + "?code=" + code + "&state=" + encode(query.get("state"));
            exchange.getResponseHeaders().add("Location", location);
            exchange.sendResponseHeaders(302,-1);
            exchange.close();
        }
        void token(HttpExchange exchange) throws IOException {
            try {
                Map<String,String> fields = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                Map<String,String> authorization = codes.remove(fields.get("code"));
                if (authorization == null || fields.get("code_verifier") == null) {
                    json(exchange,400,Map.of("error","invalid_grant")); return;
                }
                String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(fields.get("code_verifier").getBytes(StandardCharsets.US_ASCII)));
                pkceVerified = challenge.equals(authorization.get("code_challenge"));
                if (!pkceVerified) { json(exchange,400,Map.of("error","invalid_grant")); return; }
                Instant now = Instant.now();
                var claims = new JWTClaimsSet.Builder().issuer(mode == Mode.WRONG_ISSUER ? "https://wrong-issuer.test" : issuer())
                        .subject("verified-mock-user").audience(mode == Mode.WRONG_AUDIENCE ? "different-client" : "auctionhouse-test")
                        .issueTime(Date.from(now.minusSeconds(5)))
                        .expirationTime(Date.from(mode == Mode.EXPIRED ? now.minusSeconds(3600) : now.plusSeconds(300)))
                        .claim("nonce", mode == Mode.WRONG_NONCE ? "wrong-nonce" : authorization.get("nonce"))
                        .claim("name","Mock identity").build();
                var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("mock-key").build(),claims);
                token.sign(new RSASSASigner(mode == Mode.BAD_SIGNATURE ? wrongKey : key));
                json(exchange,200,Map.of("access_token","mock-provider-access-fixture", "token_type","Bearer",
                        "expires_in",300,"scope","openid profile","id_token",token.serialize()));
            } catch (Exception failure) { json(exchange,500,Map.of("error","server_error")); }
        }
        static Map<String,String> form(String encoded) {
            Map<String,String> result = new HashMap<>();
            if (encoded == null || encoded.isEmpty()) { return result; }
            for (String field : encoded.split("&")) {
                String[] pair = field.split("=",2);
                result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair.length == 1 ? "" : URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            return result;
        }
        static String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8); }
        static void json(HttpExchange exchange, int status, Object body) throws IOException {
            byte[] bytes = JsonMapper.builder().build().writeValueAsBytes(body);
            exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
