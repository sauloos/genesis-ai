package ai.genesisbrands.publishing;

import ai.genesisbrands.model.ClientUser;
import ai.genesisbrands.security.ClientAuthHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/** The signed-in client's social accounts: which platforms can be connected, and what is connected. */
@RestController
@RequestMapping("/api/client/channels")
@RequiredArgsConstructor
@Tag(name = "Channels (client)", description = "Connect social accounts for publishing")
public class ChannelClientController {

    private final PublishingService publishing;
    private final ClientAuthHelper clientAuth;

    @GetMapping
    @Operation(summary = "Platforms that can be connected, and the client's connected accounts")
    public PublishingService.ClientChannels channels(HttpServletRequest req) {
        return publishing.forClient(clientId(req));
    }

    @GetMapping("/{providerId}/connect")
    @Operation(summary = "The platform's consent page to send the browser to")
    public Map<String, String> connect(@PathVariable String providerId, @RequestParam(defaultValue = "/") String returnTo,
                                       HttpServletRequest req) {
        return Map.of("url", publishing.connectUrl(clientId(req), providerId, returnTo));
    }

    @DeleteMapping("/accounts/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Disconnect an account; its waiting posts are cancelled")
    public void disconnect(@PathVariable String accountId, HttpServletRequest req) {
        publishing.disconnect(clientId(req), accountId);
    }

    @GetMapping("/posts")
    @Operation(summary = "The client's scheduled and published posts, newest first")
    public List<PublishingService.JobView> posts(@RequestParam(defaultValue = "30") int limit, HttpServletRequest req) {
        return publishing.recentJobs(clientId(req), limit);
    }

    @DeleteMapping("/posts/{jobId}")
    @Operation(summary = "Cancel a post that is still waiting")
    public PublishingService.JobView cancel(@PathVariable String jobId, HttpServletRequest req) {
        return publishing.cancel(clientId(req), jobId);
    }

    private String clientId(HttpServletRequest req) {
        return clientAuth.resolve(req).map(ClientUser::getId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in."));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> refused(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
            .body(Map.of("message", e.getReason() == null ? "The request was refused." : e.getReason()));
    }
}
