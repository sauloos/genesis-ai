package ai.genesisbrands.publishing;

import ai.genesisbrands.model.ClientUser;
import ai.genesisbrands.security.ApiKeyExemption;
import ai.genesisbrands.security.ClientAuthHelper;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The two routes a platform reaches without our API key: the OAuth redirect (a top-level
 * browser navigation that still carries the client's session cookie) and the signed,
 * expiring image links Instagram and Facebook fetch from.
 */
@Slf4j
@Hidden
@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
public class ChannelPublicController {

    private final PublishingService publishing;
    private final ClientAuthHelper clientAuth;

    @GetMapping("/callback/{providerId}")
    public ResponseEntity<Void> callback(@PathVariable String providerId,
                                         @RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         @RequestParam(name = "error_description", required = false) String errorDescription,
                                         HttpServletRequest req) {
        String returnTo = state == null ? "/" : publishing.returnToOf(state);
        if (error != null || code == null || state == null) {
            // The client declined on the platform's screen, or the platform sent nothing usable.
            return back(returnTo, "cancelled", errorDescription == null ? "The connection was cancelled." : errorDescription);
        }
        String sessionClient = clientAuth.resolve(req).map(ClientUser::getId).orElse(null);
        try {
            PublishingService.ConnectOutcome outcome = publishing.completeConnect(providerId, code, state, sessionClient);
            return back(outcome.returnTo(), "connected", outcome.connected().size() + " account(s) connected.");
        } catch (ResponseStatusException e) {
            return back(returnTo, "error", e.getReason());
        } catch (RuntimeException e) {
            log.warn("Connecting {} failed", providerId, e);
            return back(returnTo, "error", "The connection could not be completed.");
        }
    }

    @GetMapping("/media/{token}")
    public ResponseEntity<byte[]> media(@PathVariable String token) {
        return publishing.publicMedia(token)
            .map(bytes -> ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .body(bytes))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static ResponseEntity<Void> back(String returnTo, String result, String message) {
        String sep = returnTo.contains("?") ? "&" : "?";
        String location = returnTo + sep + "channels=" + result
            + "&channelsMessage=" + URLEncoder.encode(message == null ? "" : message, StandardCharsets.UTF_8);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }

    /** Neither route can carry the shared X-Api-Key: one is a platform redirect, the other a platform fetch. */
    @Component
    public static class Routes implements ApiKeyExemption {
        @Override
        public boolean exempts(String path) {
            return path.startsWith("/api/channels/callback/") || path.startsWith("/api/channels/media/");
        }
    }
}
