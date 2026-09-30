package io.auctionhouse.auth;

import java.security.Principal;
import java.util.Objects;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonIgnore;

public record AuctionPrincipal(UUID id, String displayName, String role) implements Principal {
    public AuctionPrincipal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        if (!"USER".equals(role) && !"ADMIN".equals(role)) {
            throw new IllegalArgumentException("unsupported account role");
        }
    }

    @Override @JsonIgnore
    public String getName() { return id.toString(); }
}
