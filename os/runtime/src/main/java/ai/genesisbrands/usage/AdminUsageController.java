package ai.genesisbrands.usage;

import ai.genesisbrands.model.ClientUser;
import ai.genesisbrands.repository.ClientUserRepository;
import ai.genesisbrands.security.AdminAuthHelper;
import ai.genesisbrands.security.AdminSessionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Admin view of the usage ledger: spend by client, agent, model and day. */
@RestController
@RequestMapping("/api/admin/usage")
@RequiredArgsConstructor
public class AdminUsageController {

    private final AdminAuthHelper adminAuth;
    private final AdminSessionService adminSession;
    private final UsageService usage;
    private final ClientUserRepository clientUsers;

    public record Bucket(String key, String label, long calls, long inputTokens, long outputTokens, long images, long costMicros) {}

    public record Summary(int days, Bucket total, List<Bucket> byClient, List<Bucket> byAgent,
                          List<Bucket> byModel, List<Bucket> byDay) {}

    @GetMapping("/summary")
    public ResponseEntity<Summary> summary(@RequestParam(defaultValue = "30") int days, HttpServletRequest req) {
        if (!isAdmin(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        int window = Math.max(1, Math.min(days, 366));
        List<UsageEvent> events = usage.since(Instant.now().minus(window, ChronoUnit.DAYS));

        Map<String, String> clientLabels = new HashMap<>();
        List<String> clientIds = events.stream().map(UsageEvent::getClientUserId).filter(Objects::nonNull).distinct().toList();
        clientUsers.findAllById(clientIds).forEach(c -> clientLabels.put(c.getId(), label(c)));

        List<Bucket> byDay = group(events, e -> e.getOccurredAt().atZone(ZoneOffset.UTC).toLocalDate().toString(), k -> k);
        byDay.sort(Comparator.comparing(Bucket::key));
        return ResponseEntity.ok(new Summary(window,
            bucket("total", "All usage", events),
            group(events, UsageEvent::getClientUserId, k -> clientLabels.getOrDefault(k, k)),
            group(events, UsageEvent::getAgentId, k -> k),
            group(events, UsageEvent::getModel, k -> k),
            byDay));
    }

    public record EventRow(Instant occurredAt, String kind, String model, long inputTokens, long outputTokens,
                           int images, long costMicros, String client, String agentId, String source) {}

    @GetMapping("/events")
    public ResponseEntity<List<EventRow>> events(@RequestParam(defaultValue = "30") int days,
                                                 @RequestParam(defaultValue = "100") int limit,
                                                 HttpServletRequest req) {
        if (!isAdmin(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        List<UsageEvent> rows = usage.recent(Instant.now().minus(Math.max(1, Math.min(days, 366)), ChronoUnit.DAYS),
            Math.max(1, Math.min(limit, 500)));
        Map<String, String> clientLabels = new HashMap<>();
        clientUsers.findAllById(rows.stream().map(UsageEvent::getClientUserId).filter(Objects::nonNull).distinct().toList())
            .forEach(c -> clientLabels.put(c.getId(), label(c)));
        return ResponseEntity.ok(rows.stream().map(e -> new EventRow(e.getOccurredAt(), e.getKind().name(), e.getModel(),
            e.getInputTokens(), e.getOutputTokens(), e.getImages(), e.getCostMicros(),
            e.getClientUserId() == null ? null : clientLabels.getOrDefault(e.getClientUserId(), e.getClientUserId()),
            e.getAgentId(), e.getSource())).toList());
    }

    private boolean isAdmin(HttpServletRequest req) {
        return adminAuth.isAdminRequest(req) || adminSession.hasValidSession(req);
    }

    private static String label(ClientUser c) {
        return c.getEmail() != null ? c.getEmail() : c.getId();
    }

    /** Buckets by key, biggest spend first; a null key is its own "none" bucket. */
    private static List<Bucket> group(List<UsageEvent> events, Function<UsageEvent, String> key, Function<String, String> label) {
        Map<String, List<UsageEvent>> groups = events.stream()
            .collect(Collectors.groupingBy(e -> Objects.requireNonNullElse(key.apply(e), ""), LinkedHashMap::new, Collectors.toList()));
        return groups.entrySet().stream()
            .map(g -> bucket(g.getKey(), g.getKey().isEmpty() ? null : label.apply(g.getKey()), g.getValue()))
            .sorted(Comparator.comparingLong(Bucket::costMicros).reversed())
            .collect(Collectors.toList());
    }

    private static Bucket bucket(String key, String label, List<UsageEvent> events) {
        long in = 0, out = 0, images = 0, cost = 0;
        for (UsageEvent e : events) {
            in += e.getInputTokens();
            out += e.getOutputTokens();
            images += e.getImages();
            cost += e.getCostMicros();
        }
        return new Bucket(key, label, events.size(), in, out, images, cost);
    }
}
