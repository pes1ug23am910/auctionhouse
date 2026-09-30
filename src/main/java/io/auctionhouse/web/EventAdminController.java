package io.auctionhouse.web;

import io.auctionhouse.outbox.EventReconciliation;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/admin/event-cuts")
public class EventAdminController {
    private final EventReconciliation cuts;
    private final ObjectMapper json;
    public EventAdminController(EventReconciliation cuts, ObjectMapper json) { this.cuts=cuts; this.json=json; }
    @PostMapping public Map<String,UUID> capture() { return Map.of("cutId", cuts.capture()); }
    @GetMapping("/{id}") public EventReconciliation.Report report(@PathVariable UUID id) { return cuts.report(id); }
    @GetMapping("/{id}/notifications") public List<JsonNode> notifications(@PathVariable UUID id,
            @RequestParam(required=false) UUID after, @RequestParam(defaultValue="100") int limit) {
        return cuts.notifications(id,after,limit).stream().map(json::readTree).toList();
    }
    @GetMapping("/{id}/events") public List<JsonNode> events(@PathVariable UUID id,
            @RequestParam(required=false) UUID after, @RequestParam(defaultValue="100") int limit) {
        return cuts.events(id,after,limit).stream().map(event -> json.readTree(event.envelope())).toList();
    }
}
