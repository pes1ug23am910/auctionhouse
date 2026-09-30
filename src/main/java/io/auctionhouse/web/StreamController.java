package io.auctionhouse.web;

import io.auctionhouse.auth.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auctions/{id}")
public class StreamController {
    private final AuctionStreams streams;
    private final AuthService auth;
    public StreamController(AuctionStreams streams, AuthService auth) { this.streams = streams; this.auth = auth; }
    @GetMapping("/snapshot") public AuctionStreams.Snapshot snapshot(@AuthenticationPrincipal AuctionPrincipal actor, @PathVariable UUID id) {
        return streams.snapshot(actor.id(),id);
    }
    @GetMapping(value="/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public void events(@AuthenticationPrincipal AuctionPrincipal actor, @PathVariable UUID id,
            @RequestHeader(name="Last-Event-ID",required=false) String lastEventId,
            @RequestParam(required=false) String cursor, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        String token=AccessTokenFilter.token(request);
        String resume=lastEventId!=null && !lastEventId.isBlank() ? lastEventId : cursor;
        streams.open(actor.id(),id,resume,
                () -> auth.authenticate(token).filter(current -> current.id().equals(actor.id())).isPresent(),
                request,response);
    }
}
