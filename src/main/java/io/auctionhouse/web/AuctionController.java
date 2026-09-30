package io.auctionhouse.web;

import io.auctionhouse.auction.*;
import io.auctionhouse.auth.AuctionPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auctions")
public class AuctionController {
    private final AuctionService auctions;
    private final AuctionBrowse browse;
    public AuctionController(AuctionService auctions,AuctionBrowse browse) { this.auctions=auctions; this.browse=browse; }

    @GetMapping("/bid-policy") public Map<String,Object> bidPolicy() {
        return Map.of("replayWindowSeconds",2592000,"deduplicationRetention","indefinite",
                "absentOutcome","unknown","expiredOutcomeCode","INTENT_EXPIRED");
    }

    public record CreateAuction(@NotBlank @Size(max=160) String title, @NotNull @Size(max=4000) String description,
        @Min(1) @Max(9000000000000000L) long openingPriceMinor,
        @Min(1) @Max(9000000000000000L) long minimumIncrementMinor, @NotNull Instant endsAt) { }
    public record PlaceBid(@Min(1) @Max(9000000000000000L) long amountMinor) { }

    @GetMapping public List<AuctionSnapshot> list(@AuthenticationPrincipal AuctionPrincipal actor,
            @RequestParam(defaultValue="0") int offset, @RequestParam(defaultValue="24") int limit,
            @RequestParam(defaultValue="false") boolean mine) {
        return auctions.list(actor.id(), offset, limit, mine);
    }
    @PostMapping public ResponseEntity<AuctionSnapshot> create(@AuthenticationPrincipal AuctionPrincipal actor,
            @Valid @RequestBody CreateAuction input) {
        var created = auctions.create(actor.id(), input.title(), input.description(), input.openingPriceMinor(), input.minimumIncrementMinor(), input.endsAt());
        return ResponseEntity.created(java.net.URI.create("/api/auctions/" + created.id())).body(created);
    }
    @GetMapping("/{id}") public AuctionSnapshot get(@AuthenticationPrincipal AuctionPrincipal actor, @PathVariable UUID id) {
        return browse.get(actor.id(), id);
    }
    @PostMapping("/{id}/publish") public AuctionSnapshot publish(@AuthenticationPrincipal AuctionPrincipal actor, @PathVariable UUID id) {
        return auctions.publish(actor.id(), id);
    }
    @PostMapping("/{id}/close") public AuctionSnapshot close(@AuthenticationPrincipal AuctionPrincipal actor, @PathVariable UUID id) {
        return auctions.close(actor.id(), id);
    }
    @PostMapping("/{id}/cancel") public AuctionSnapshot cancel(@AuthenticationPrincipal AuctionPrincipal actor, @PathVariable UUID id) {
        return auctions.cancel(actor.id(), id);
    }
    @PostMapping("/{id}/bids") public ResponseEntity<BidOutcome> bid(@AuthenticationPrincipal AuctionPrincipal actor,
            @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value="X-Expected-Actor", required=false) UUID expectedActor,
            @Valid @RequestBody PlaceBid input) {
        requireExpectedActor(actor, expectedActor);
        var outcome = auctions.bid(actor.id(), id, key, input.amountMinor());
        return ResponseEntity.status(outcome.accepted() ? 200 : 422).body(outcome);
    }
    @GetMapping("/{id}/bid-intents/{key}") public ResponseEntity<?> intent(@AuthenticationPrincipal AuctionPrincipal actor,
            @PathVariable UUID id, @PathVariable String key,
            @RequestHeader(value="X-Expected-Actor", required=false) UUID expectedActor) {
        requireExpectedActor(actor, expectedActor);
        return auctions.intent(actor.id(), id, key).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("code","INTENT_UNKNOWN", "message","No retained outcome is available for this actor and key")));
    }
    private static void requireExpectedActor(AuctionPrincipal actor, UUID expectedActor) {
        if (expectedActor != null && !expectedActor.equals(actor.id())) {
            throw new AuctionException("ACTOR_CHANGED", 409,
                    "Sign in to the account that created this bid intent before continuing");
        }
    }

    @GetMapping("/{id}/bids") public List<Map<String,Object>> bids(@AuthenticationPrincipal AuctionPrincipal actor,
            @PathVariable UUID id, @RequestParam(defaultValue="9223372036854775807") long beforeVersion,
            @RequestParam(defaultValue="30") int limit) {
        return auctions.bids(actor.id(), id, beforeVersion, limit);
    }
}
