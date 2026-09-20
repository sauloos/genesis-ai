package ai.genesisbrands.controller;

import ai.genesisbrands.service.BlobStorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;

/**
 * Streams blob-stored assets (currently: generated logo images) back over HTTP so the
 * Playground UI and LogoEvaluator's vision review can reference them by URL.
 */
@RestController
public class AssetController {

    private static final String PREFIX = "/api/assets/";

    private final BlobStorageService blobStorageService;

    public AssetController(BlobStorageService blobStorageService) {
        this.blobStorageService = blobStorageService;
    }

    @GetMapping("/api/assets/**")
    public ResponseEntity<ByteArrayResource> get(HttpServletRequest request) {
        String path = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String blobPath = path.startsWith(PREFIX) ? path.substring(PREFIX.length()) : path;

        try {
            byte[] bytes = blobStorageService.download(blobPath);
            return ResponseEntity.ok()
                .contentType(mediaTypeFor(blobPath))
                .body(new ByteArrayResource(bytes));
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    private MediaType mediaTypeFor(String blobPath) {
        if (blobPath.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (blobPath.endsWith(".jpg") || blobPath.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (blobPath.endsWith(".svg")) return MediaType.valueOf("image/svg+xml");
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
