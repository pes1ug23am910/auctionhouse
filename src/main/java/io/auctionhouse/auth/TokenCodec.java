package io.auctionhouse.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class TokenCodec {
    private static final Pattern ACCESS = Pattern.compile("ah_access_[A-Za-z0-9_-]{43}");
    private static final Pattern REFRESH = Pattern.compile("ah_refresh_[A-Za-z0-9_-]{43}");
    private final SecureRandom random = new SecureRandom();

    public String accessToken() { return token("ah_access_"); }
    public String refreshToken() { return token("ah_refresh_"); }
    public static boolean isAccess(String value) { return value != null && ACCESS.matcher(value).matches(); }
    public static boolean isRefresh(String value) { return value != null && REFRESH.matcher(value).matches(); }

    public static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("required digest unavailable", impossible);
        }
    }

    private String token(String prefix) {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }
}
