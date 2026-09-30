package io.auctionhouse.auth;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class AuthServiceIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            org.testcontainers.utility.DockerImageName.parse("postgres@sha256:5a5a84b19854a9ffaa54082c166ff4ec27473a361e496e5ea167f298f2da9722").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager manager;
    AuthService auth;
    AuctionPrincipal account;

    @BeforeAll static void database() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        manager = new DataSourceTransactionManager(dataSource);
    }
    @BeforeEach void fixture() {
        auth = service();
        account = auth.findOrCreateAccount("https://issuer.test", UUID.randomUUID().toString(), "Test user");
    }
    static AuthService service() {
        return new AuthService(jdbc, manager, new TokenCodec(), new AuthSettings(
                Duration.ofMinutes(10), Duration.ofDays(7), Duration.ofDays(30), false));
    }

    @Test void validatedIssuerAndSubjectIdentifyAccountWithoutChangingLocalRole() {
        jdbc.update("UPDATE accounts SET role='ADMIN' WHERE id=?", account.id());
        String subject = jdbc.queryForObject("SELECT subject FROM accounts WHERE id=?", String.class, account.id());
        AuctionPrincipal again = auth.findOrCreateAccount("https://issuer.test", subject, "Updated name");
        assertEquals(account.id(), again.id());
        assertEquals("ADMIN", again.role());
        assertEquals("Updated name", again.displayName());
        assertNotEquals(account.id(), auth.findOrCreateAccount("https://another.test", subject, "Other").id());
    }

    @Test void anotherInstanceAuthenticatesStoredHashesAndReadsCurrentLocalRole() {
        TokenPair pair = auth.issue(new AuctionPrincipal(account.id(), "Spoofed", "ADMIN"));
        assertEquals(account, service().authenticate(pair.accessToken()).orElseThrow());
        List<String> hashes = jdbc.queryForList("""
                SELECT token_hash FROM auth_access_tokens WHERE family_id IN
                (SELECT id FROM auth_families WHERE account_id=?) UNION ALL
                SELECT token_hash FROM auth_refresh_tokens WHERE family_id IN
                (SELECT id FROM auth_families WHERE account_id=?)
                """, String.class, account.id(), account.id());
        assertEquals(2, hashes.size());
        assertTrue(hashes.stream().allMatch(hash -> hash.matches("[0-9a-f]{64}")));
        assertFalse(hashes.contains(pair.accessToken()));
        assertFalse(hashes.contains(pair.refreshToken()));
        jdbc.update("UPDATE accounts SET role='ADMIN' WHERE id=?", account.id());
        assertEquals("ADMIN", service().authenticate(pair.accessToken()).orElseThrow().role());
    }

    @Test void rotationAndReuseRevocationSurviveFailedResponseAndDifferentInstance() {
        TokenPair original = auth.issue(account);
        TokenPair rotated = service().refresh(original.refreshToken());
        assertFalse(original.refreshToken().equals(rotated.refreshToken()), "rotation must replace the refresh secret");
        assertEquals(account.id(), auth.authenticate(rotated.accessToken()).orElseThrow().id());
        AuthFailure failure = assertThrows(AuthFailure.class, () -> service().refresh(original.refreshToken()));
        assertEquals("REFRESH_REUSE", failure.code());
        assertTrue(service().authenticate(original.accessToken()).isEmpty());
        assertTrue(service().authenticate(rotated.accessToken()).isEmpty());
        assertThrows(AuthFailure.class, () -> auth.refresh(rotated.refreshToken()));
        assertEquals("REFRESH_REUSE", jdbc.queryForObject(
                "SELECT revocation_reason FROM auth_families WHERE account_id=?", String.class, account.id()));
    }

    @Test void simultaneousRefreshHasOneRotationAndStrictlyRevokesItsNewTokens() throws Exception {
        TokenPair original = auth.issue(account);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var attempt = (java.util.concurrent.Callable<Object>) () -> {
                barrier.await();
                try { return service().refresh(original.refreshToken()); }
                catch (AuthFailure failure) { return failure.code(); }
            };
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            Object left = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            Object right = second.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(left instanceof TokenPair, right instanceof TokenPair,
                    "exactly one request consumes the refresh token");
            TokenPair successful = left instanceof TokenPair pair ? pair : (TokenPair) right;
            assertTrue(auth.authenticate(successful.accessToken()).isEmpty(),
                    "the strict reuse policy revokes even the first caller's new access token");
            assertTrue(auth.authenticate(original.accessToken()).isEmpty());
            assertEquals(2, jdbc.queryForObject("""
                    SELECT count(*) FROM auth_refresh_tokens WHERE family_id IN
                    (SELECT id FROM auth_families WHERE account_id=?)
                    """, Integer.class, account.id()));
        }
    }

    @Test void expiredAccessCannotAuthenticateAndExpiredRefreshCannotRotate() {
        TokenPair pair = auth.issue(account);
        jdbc.update("""
                UPDATE auth_access_tokens SET created_at=clock_timestamp()-interval '2 minutes',
                expires_at=clock_timestamp()-interval '1 minute' WHERE token_hash=?
                """, TokenCodec.digest(pair.accessToken()));
        assertTrue(auth.authenticate(pair.accessToken()).isEmpty());
        jdbc.update("""
                UPDATE auth_refresh_tokens SET created_at=clock_timestamp()-interval '2 minutes',
                expires_at=clock_timestamp()-interval '1 minute' WHERE token_hash=?
                """, TokenCodec.digest(pair.refreshToken()));
        assertEquals("EXPIRED_REFRESH", assertThrows(AuthFailure.class,
                () -> auth.refresh(pair.refreshToken())).code());
    }

    @Test void absoluteFamilyExpiryLimitsRefreshAndAccessEvenWhenTokenRowsLastLonger() {
        TokenPair pair = auth.issue(account);
        jdbc.update("""
                UPDATE auth_families SET created_at=clock_timestamp()-interval '2 minutes',
                expires_at=clock_timestamp()-interval '1 minute' WHERE account_id=?
                """, account.id());
        assertTrue(auth.authenticate(pair.accessToken()).isEmpty());
        assertThrows(AuthFailure.class, () -> auth.refresh(pair.refreshToken()));
    }

    @Test void logoutIsDurableAndDoesNotRevokeAnotherSessionFamily() {
        TokenPair first = auth.issue(account);
        TokenPair second = auth.issue(account);
        auth.logout(first.refreshToken(), first.accessToken());
        service().logout(first.refreshToken(), first.accessToken());
        assertTrue(service().authenticate(first.accessToken()).isEmpty());
        assertThrows(AuthFailure.class, () -> service().refresh(first.refreshToken()));
        assertEquals(account.id(), service().authenticate(second.accessToken()).orElseThrow().id());
    }

    @Test void rotationCannotExtendAbsoluteFamilyDeadline() {
        TokenPair pair = auth.issue(account);
        jdbc.update("UPDATE auth_families SET expires_at=clock_timestamp()+interval '1 minute' WHERE account_id=?", account.id());
        var maximum = jdbc.queryForObject("SELECT expires_at FROM auth_families WHERE account_id=?",
                java.sql.Timestamp.class, account.id()).toInstant();
        TokenPair rotated = auth.refresh(pair.refreshToken());
        assertEquals(maximum, rotated.accessExpiresAt());
        assertEquals(maximum, rotated.refreshExpiresAt());
    }
}
