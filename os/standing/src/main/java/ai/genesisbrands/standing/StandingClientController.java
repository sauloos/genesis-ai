package ai.genesisbrands.standing;

import ai.genesisbrands.model.ClientUser;
import ai.genesisbrands.security.ClientAuthHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * The signed-in client's standing agents: what is offered, their subscriptions, their runs
 * and their inbox. The client is always the session's, never a request parameter.
 */
@RestController
@RequestMapping("/api/client/standing")
@RequiredArgsConstructor
@Tag(name = "Standing agents (client)", description = "Subscribe to agents that keep working on a schedule; review their output")
public class StandingClientController {

    private final StandingService standing;
    private final ClientAuthHelper clientAuth;

    public record Decision(String feedback) {}

    @GetMapping("/offers")
    @Operation(summary = "Agents the client may subscribe to, with their current subscription")
    public List<StandingService.ClientOffer> offers(HttpServletRequest req) {
        return standing.offersFor(clientId(req));
    }

    @PutMapping("/subscriptions/{agentId}")
    @Operation(summary = "Subscribe to an agent, or change the subscription's frequency and options")
    public StandingService.SubscriptionView subscribe(@PathVariable String agentId,
                                                      @RequestBody StandingService.SubscriptionRequest body,
                                                      HttpServletRequest req) {
        return standing.subscribe(clientId(req), agentId, body);
    }

    @DeleteMapping("/subscriptions/{agentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Unsubscribe; inbox items already delivered are kept")
    public void unsubscribe(@PathVariable String agentId, HttpServletRequest req) {
        standing.unsubscribe(clientId(req), agentId);
    }

    @PostMapping("/subscriptions/{agentId}/pause")
    public StandingService.SubscriptionView pause(@PathVariable String agentId, HttpServletRequest req) {
        return standing.pause(clientId(req), agentId);
    }

    @PostMapping("/subscriptions/{agentId}/resume")
    public StandingService.SubscriptionView resume(@PathVariable String agentId, HttpServletRequest req) {
        return standing.resume(clientId(req), agentId);
    }

    @PostMapping("/subscriptions/{agentId}/run-now")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Run the agent now; its output arrives in the inbox when the run finishes")
    public StandingService.RunView runNow(@PathVariable String agentId, HttpServletRequest req) {
        return standing.runNow(clientId(req), agentId);
    }

    @GetMapping("/runs")
    public List<StandingService.RunView> runs(@RequestParam(defaultValue = "20") int limit, HttpServletRequest req) {
        return standing.runsFor(clientId(req), limit);
    }

    @GetMapping("/inbox")
    @Operation(summary = "Inbox items, newest first; status = PENDING, APPROVED, REJECTED or ALL")
    public List<StandingService.InboxView> inbox(@RequestParam(defaultValue = "ALL") String status,
                                                 @RequestParam(defaultValue = "30") int limit,
                                                 HttpServletRequest req) {
        return standing.inboxFor(clientId(req), status, limit);
    }

    @GetMapping("/inbox/count")
    public Map<String, Long> pending(HttpServletRequest req) {
        return Map.of("pending", standing.pendingCount(clientId(req)));
    }

    @GetMapping("/inbox/{itemId}")
    public StandingService.InboxView item(@PathVariable String itemId, HttpServletRequest req) {
        return standing.inboxItem(clientId(req), itemId);
    }

    @PostMapping("/inbox/{itemId}/approve")
    public StandingService.InboxView approve(@PathVariable String itemId, @RequestBody(required = false) Decision body,
                                             HttpServletRequest req) {
        return standing.decide(clientId(req), itemId, true, body == null ? null : body.feedback());
    }

    @PostMapping("/inbox/{itemId}/reject")
    public StandingService.InboxView reject(@PathVariable String itemId, @RequestBody(required = false) Decision body,
                                            HttpServletRequest req) {
        return standing.decide(clientId(req), itemId, false, body == null ? null : body.feedback());
    }

    @GetMapping("/inbox/{itemId}/attachments/{index}")
    @Operation(summary = "One file of an inbox item, such as a post image; ?download=true saves it")
    public ResponseEntity<byte[]> attachment(@PathVariable String itemId, @PathVariable int index,
                                             @RequestParam(defaultValue = "false") boolean download,
                                             HttpServletRequest req) {
        StandingService.AttachmentContent file = standing.attachment(clientId(req), itemId, index);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(file.file().contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(file.file().name()).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
            .header("X-Content-Type-Options", "nosniff")
            .body(file.data());
    }

    @GetMapping("/inbox/{itemId}/download")
    @Operation(summary = "An inbox item as a zip: its text, each channel's version and its files")
    public ResponseEntity<byte[]> download(@PathVariable String itemId, HttpServletRequest req) {
        byte[] zip = standing.export(clientId(req), itemId);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/zip"))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("inbox-" + itemId.substring(0, Math.min(8, itemId.length())) + ".zip").build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(zip);
    }

    private String clientId(HttpServletRequest req) {
        return clientAuth.resolve(req).map(ClientUser::getId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in required"));
    }

    /** The service's reasons are written for the person on screen; the default error body drops them. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> refused(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
            .body(Map.of("message", e.getReason() == null ? "The request was refused." : e.getReason()));
    }
}
