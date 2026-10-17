package ai.genesisbrands.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.*;

/**
 * Fetches brand-appropriate stock photography from Unsplash and stores it in blob
 * storage. Each photo is assigned to a named slot used by the brand book template.
 *
 * Slots: cover, mission, vision, values, imagery-1…5, examples-1…3
 * (13 slots total — enough to fill all photo pages in the 26-page template)
 */
@Service
public class PhotoSourcingService {

    private static final Logger log = LoggerFactory.getLogger(PhotoSourcingService.class);

    private static final List<String> SLOTS = List.of(
        "cover", "mission", "vision", "values",
        "imagery-1", "imagery-2", "imagery-3", "imagery-4", "imagery-5",
        "examples-1", "examples-2", "examples-3",
        "stationery"
    );

    private final BlobStorageService blobStorageService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${unsplash.access-key:}")
    private String unsplashAccessKey;

    public PhotoSourcingService(BlobStorageService blobStorageService, ObjectMapper objectMapper) {
        this.blobStorageService = blobStorageService;
        this.objectMapper = objectMapper;
    }

    /**
     * Fetches one photo per slot using the provided search keywords (cycling through
     * the list if there are fewer keywords than slots) and stores each in blob storage.
     *
     * @return map of slot name → blob path, e.g. {"cover" → "assets/photos/eng123/cover.jpg"}
     */
    public Map<String, String> fetchAndStore(String engagementId, List<String> keywords) {
        if (unsplashAccessKey == null || unsplashAccessKey.isBlank()) {
            log.warn("PhotoSourcingService: UNSPLASH_ACCESS_KEY not configured — skipping photo sourcing");
            return Map.of();
        }
        if (keywords == null || keywords.isEmpty()) {
            log.warn("PhotoSourcingService: no keywords provided for engagement {}", engagementId);
            return Map.of();
        }

        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < SLOTS.size(); i++) {
            String slot = SLOTS.get(i);
            String keyword = keywords.get(i % keywords.size());
            try {
                String blobPath = fetchAndStoreOne(engagementId, slot, keyword);
                if (blobPath != null) result.put(slot, blobPath);
            } catch (Exception e) {
                log.warn("PhotoSourcingService: failed to fetch photo for slot '{}' (keyword: '{}') — {}",
                    slot, keyword, e.getMessage());
            }
        }
        log.info("PhotoSourcingService: sourced {}/{} photos for engagement {}", result.size(), SLOTS.size(), engagementId);
        return result;
    }

    private String fetchAndStoreOne(String engagementId, String slot, String keyword) throws IOException {
        URI uri = UriComponentsBuilder
            .fromUriString("https://api.unsplash.com/search/photos")
            .queryParam("query", keyword)
            .queryParam("per_page", 1)
            .queryParam("orientation", slot.equals("cover") ? "landscape" : "landscape")
            .build()
            .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Client-ID " + unsplashAccessKey);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<String> response = restTemplate.exchange(
            uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
            log.warn("Unsplash returned {} for keyword '{}'", response.getStatusCode(), keyword);
            return null;
        }

        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode results = root.path("results");
        if (!results.isArray() || results.isEmpty()) {
            log.warn("No Unsplash results for keyword '{}'", keyword);
            return null;
        }

        // Prefer "regular" size (~1080px wide) — good for print without being enormous
        String imageUrl = results.get(0).path("urls").path("regular").asText(null);
        if (imageUrl == null || imageUrl.isBlank()) return null;

        byte[] imageBytes = restTemplate.getForObject(imageUrl, byte[].class);
        if (imageBytes == null || imageBytes.length == 0) return null;

        String blobPath = "assets/photos/%s/%s.jpg".formatted(engagementId, slot);
        blobStorageService.upload(blobPath, imageBytes);
        return blobPath;
    }
}
