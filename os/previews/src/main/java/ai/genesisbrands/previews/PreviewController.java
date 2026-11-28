package ai.genesisbrands.previews;

import ai.genesisbrands.model.ClientUser;
import ai.genesisbrands.platform.RegisteredByConfiguration;
import ai.genesisbrands.security.ClientAuthHelper;
import ai.genesisbrands.service.DocumentPreviewProvider.Document;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * View-only previews for the signed-in client. Registered only through
 * {@link PreviewsConfiguration} (see {@code ClientWorkspaceController} for why), and reachable
 * only with the client's own session: connector keys never open it, by design.
 */
@RestController
@RegisteredByConfiguration
@RequestMapping("/api/client/previews")
@RequiredArgsConstructor
@Tag(name = "Document previews", description = "Watermarked, view-only page images of documents a client may look inside")
public class PreviewController {

    private final DocumentPreviewService service;
    private final ClientAuthHelper clientAuth;

    @GetMapping
    @Operation(summary = "The documents the signed-in client may preview")
    public List<Document> mine(HttpServletRequest req) {
        return service.list(client(req).getId());
    }

    @GetMapping("/{workItemId}/{variantKey}/manifest")
    @Operation(summary = "Page sequence of one document, with a signed expiring link per viewable page")
    public ResponseEntity<DocumentPreviewService.Manifest> manifest(
        @PathVariable String workItemId, @PathVariable String variantKey, HttpServletRequest req) {
        ClientUser user = client(req);
        return noStore(ResponseEntity.ok(), null)
            .body(service.manifest(user.getId(), workItemId, variantKey, req.getRemoteAddr(), req.getHeader(HttpHeaders.USER_AGENT)));
    }

    @GetMapping("/p/{token:.+}")
    @Operation(summary = "One watermarked page image")
    public ResponseEntity<byte[]> page(@PathVariable String token, HttpServletRequest req) {
        ClientUser user = client(req);
        DocumentPreviewService.Rendition r = service.page(token, user.getId(),
            DocumentPreviewService.maskIdentity(user.getEmail()), req.getRemoteAddr(), req.getHeader(HttpHeaders.USER_AGENT));
        return noStore(ResponseEntity.ok(), MediaType.IMAGE_JPEG).body(r.jpeg());
    }

    /** Never cached, never sniffed, never embedded by another site, never indexed. */
    private static ResponseEntity.BodyBuilder noStore(ResponseEntity.BodyBuilder b, MediaType type) {
        b.cacheControl(CacheControl.noStore().cachePrivate())
            .header("Pragma", "no-cache")
            .header("X-Content-Type-Options", "nosniff")
            .header("Cross-Origin-Resource-Policy", "same-origin")
            .header("X-Robots-Tag", "noindex, noarchive");
        if (type != null) b.contentType(type).header(HttpHeaders.CONTENT_DISPOSITION, "inline");
        return b;
    }

    private ClientUser client(HttpServletRequest req) {
        return clientAuth.resolve(req)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in required"));
    }
}
