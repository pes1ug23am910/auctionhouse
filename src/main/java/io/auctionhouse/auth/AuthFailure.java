package io.auctionhouse.auth;

public final class AuthFailure extends RuntimeException {
    private final String code;
    public AuthFailure(String code) {
        super("Authentication is required or the supplied session is no longer valid");
        this.code = code;
    }
    public String code() { return code; }
}
