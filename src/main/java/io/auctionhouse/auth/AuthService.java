package io.auctionhouse.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TokenCodec tokens;
    private final AuthSettings settings;

    public AuthService(JdbcTemplate jdbc, PlatformTransactionManager manager,
                       TokenCodec tokens, AuthSettings settings) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.tokens = tokens;
        this.settings = settings;
    }

    public AuctionPrincipal findOrCreateAccount(String issuer, String subject, String displayName) {
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new AuthFailure("INVALID_IDENTITY");
        }
        String name = displayName == null || displayName.isBlank() ? subject : displayName;
        name = name.substring(0, Math.min(120, name.length()));
        return jdbc.queryForObject("""
                INSERT INTO accounts(id,issuer,subject,display_name) VALUES (?,?,?,?)
                ON CONFLICT(issuer,subject) DO UPDATE SET display_name=EXCLUDED.display_name
                RETURNING id,display_name,role
                """, AuthService::principal, UUID.randomUUID(), issuer, subject, name);
    }

    public TokenPair issue(AuctionPrincipal account) {
        Objects.requireNonNull(account, "account");
        return transactions.execute(status -> {
            Instant now = now();
            UUID family = UUID.randomUUID();
            Instant expiry = now.plus(settings.familyLifetime());
            jdbc.update("INSERT INTO auth_families(id,account_id,created_at,expires_at) VALUES (?,?,?,?)",
                    family, account.id(), Timestamp.from(now), Timestamp.from(expiry));
            return issueTokens(family, now, expiry);
        });
    }

    public Optional<AuctionPrincipal> authenticate(String rawAccessToken) {
        if (!TokenCodec.isAccess(rawAccessToken)) { return Optional.empty(); }
        return jdbc.query("""
                SELECT a.id,a.display_name,a.role FROM auth_access_tokens t
                JOIN auth_families f ON f.id=t.family_id
                JOIN accounts a ON a.id=f.account_id
                WHERE t.token_hash=? AND t.expires_at>clock_timestamp()
                  AND f.revoked_at IS NULL AND f.expires_at>clock_timestamp()
                """, AuthService::principal, TokenCodec.digest(rawAccessToken)).stream().findFirst();
    }

    public TokenPair refresh(String rawRefreshToken) {
        if (!TokenCodec.isRefresh(rawRefreshToken)) { throw new AuthFailure("INVALID_REFRESH"); }
        String digest = TokenCodec.digest(rawRefreshToken);
        Rotation result = transactions.execute(status -> rotate(digest));
        // Reuse revocation must commit even though the caller receives an authentication failure.
        if (result == null || result.failure() != null) {
            throw new AuthFailure(result == null ? "INVALID_REFRESH" : result.failure());
        }
        return result.tokens();
    }

    public void logout(String rawRefreshToken, String rawAccessToken) {
        transactions.executeWithoutResult(status -> {
            String refreshHash = TokenCodec.isRefresh(rawRefreshToken) ? TokenCodec.digest(rawRefreshToken) : null;
            String accessHash = TokenCodec.isAccess(rawAccessToken) ? TokenCodec.digest(rawAccessToken) : null;
            List<UUID> families = jdbc.query("""
                    SELECT id FROM auth_families WHERE id IN
                    (SELECT family_id FROM auth_refresh_tokens WHERE token_hash=? UNION
                     SELECT family_id FROM auth_access_tokens WHERE token_hash=?)
                    ORDER BY id FOR UPDATE
                    """, (rs,row) -> rs.getObject(1,UUID.class), refreshHash, accessHash);
            for (UUID family : families) {
                jdbc.update("""
                        UPDATE auth_families SET revoked_at=COALESCE(revoked_at,clock_timestamp()),
                        revocation_reason=COALESCE(revocation_reason,'LOGOUT') WHERE id=?
                        """, family);
            }
        });
    }

    private Rotation rotate(String hash) {
        List<UUID> identities = jdbc.query("SELECT family_id FROM auth_refresh_tokens WHERE token_hash=?",
                (rs, row) -> rs.getObject(1, UUID.class), hash);
        if (identities.isEmpty()) { return Rotation.failed("INVALID_REFRESH"); }
        UUID family = identities.getFirst();
        Family state = jdbc.queryForObject("SELECT expires_at,revoked_at FROM auth_families WHERE id=? FOR UPDATE",
                (rs, row) -> new Family(rs.getTimestamp(1).toInstant(), rs.getTimestamp(2) != null), family);
        Instant now = now();
        if (state == null || state.revoked() || !now.isBefore(state.expiresAt())) {
            return Rotation.failed("EXPIRED_OR_REVOKED");
        }
        // Read consumption only after acquiring the shared family lock; a waiting instance sees the prior commit.
        Refresh refresh = jdbc.queryForObject("SELECT expires_at,consumed_at FROM auth_refresh_tokens WHERE token_hash=?",
                (rs, row) -> new Refresh(rs.getTimestamp(1).toInstant(), rs.getTimestamp(2) != null), hash);
        if (refresh == null) { return Rotation.failed("INVALID_REFRESH"); }
        if (refresh.consumed()) {
            jdbc.update("UPDATE auth_families SET revoked_at=?,revocation_reason='REFRESH_REUSE' WHERE id=?",
                    Timestamp.from(now), family);
            return Rotation.failed("REFRESH_REUSE");
        }
        if (!now.isBefore(refresh.expiresAt())) { return Rotation.failed("EXPIRED_REFRESH"); }
        jdbc.update("UPDATE auth_refresh_tokens SET consumed_at=? WHERE token_hash=?", Timestamp.from(now), hash);
        return new Rotation(issueTokens(family, now, state.expiresAt()), null);
    }

    private TokenPair issueTokens(UUID family, Instant now, Instant familyExpiry) {
        String access = tokens.accessToken();
        String refresh = tokens.refreshToken();
        Instant accessExpiry = earlier(now.plus(settings.accessLifetime()), familyExpiry);
        Instant refreshExpiry = earlier(now.plus(settings.refreshLifetime()), familyExpiry);
        jdbc.update("INSERT INTO auth_access_tokens(token_hash,family_id,created_at,expires_at) VALUES (?,?,?,?)",
                TokenCodec.digest(access), family, Timestamp.from(now), Timestamp.from(accessExpiry));
        jdbc.update("INSERT INTO auth_refresh_tokens(token_hash,family_id,created_at,expires_at) VALUES (?,?,?,?)",
                TokenCodec.digest(refresh), family, Timestamp.from(now), Timestamp.from(refreshExpiry));
        return new TokenPair(access, refresh, accessExpiry, refreshExpiry);
    }

    private Instant now() { return Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class)).toInstant(); }
    private static Instant earlier(Instant first, Instant second) { return first.isBefore(second) ? first : second; }
    private static AuctionPrincipal principal(ResultSet rs, int row) throws SQLException {
        return new AuctionPrincipal(rs.getObject("id", UUID.class), rs.getString("display_name"), rs.getString("role"));
    }
    private record Family(Instant expiresAt, boolean revoked) { }
    private record Refresh(Instant expiresAt, boolean consumed) { }
    private record Rotation(TokenPair tokens, String failure) {
        static Rotation failed(String reason) { return new Rotation(null, reason); }
    }
}
