package ai.genesisbrands.service;

import ai.genesisbrands.agent.core.DirectionBrief;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.*;

/**
 * Sources stock photography from Unsplash for one direction's brand book and stores it in blob
 * storage, one photo per slot the templates use, at {@code assets/photos/<eng>/<DIRECTION>/<slot>.jpg}.
 *
 * <p>Each direction gets its own photos, searched with its own image keywords, and no photo is
 * used by two directions of the same engagement: a {@code photos.json} manifest beside the photos
 * records the Unsplash ids (and the photographer credit), and a direction skips every id its
 * siblings' manifests already hold. Until 2026-11-26 all three books shared one engagement-wide
 * set fetched with {@code per_page=1}, so the anchored, evolved and disruptive books carried the
 * same pictures, and a re-render re-fetched (and replaced) them every time.
 *
 * <p>One search per keyword ({@code per_page=30}, {@code content_filter=high}) feeds several slots,
 * which keeps a full book to a handful of API calls. Full-page slots (cover, mission) prefer a
 * portrait photo, since the book is A4 portrait.
 */
@Service
public class PhotoSourcingService {

    private static final Logger log = LoggerFactory.getLogger(PhotoSourcingService.class);

    /** The slots the brand book templates show (BrandBookTemplateRenderer.PHOTO_SLOTS). */
    static final List<String> SLOTS = List.of(
        "cover", "mission",
        "imagery-1", "imagery-2", "imagery-3", "imagery-4", "imagery-5",
        "examples-1", "examples-2", "examples-3",
        "stationery"
    );

    private static final Set<String> FULL_PAGE = Set.of("cover", "mission");

    /**
     * Words in a photo's Unsplash description that mean someone else's branding is in frame: a
     * competitor's bag or shop sign has no place in a client's brand book (2026-11-26, a stock
     * "coffee bag" search filled a roastery's book with other roasters' packaging). A keyword that
     * asks for such a subject is not searched at all.
     */
    static final java.util.regex.Pattern BRANDED = java.util.regex.Pattern.compile(
        "(?i)\\b(logos?|labell?ed|labels?|brand(ed|ing)?|packag(e|es|ing)|signs?|signage|storefront|shopfront|"
            + "bags?|parcels?|poster|billboard|neon|text|lettering|typography|menu|sticker)\\b");

    /** Searches per direction: enough variety without spending the Unsplash hourly quota. */
    static final int MAX_KEYWORDS = 6;

    private final BlobStorageService blobStorageService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${unsplash.access-key:}")
    private String unsplashAccessKey;

    public PhotoSourcingService(BlobStorageService blobStorageService, ObjectMapper objectMapper) {
        this.blobStorageService = blobStorageService;
        this.objectMapper = objectMapper;
    }

    /** One stored photo: the slot it fills, its Unsplash id and who took it. */
    public record SourcedPhoto(String slot, String id, String photographer, String profileUrl) {}

    /** A candidate from a search. */
    record Candidate(String id, String imageUrl, String downloadLocation, int width, int height,
                     String photographer, String profileUrl) {
        boolean portrait() { return height > width; }
    }

    /** The direction already has its photos: a re-render keeps them instead of fetching new ones. */
    public boolean hasPhotos(String engagementId, String direction) {
        return !readManifest(engagementId, direction).isEmpty();
    }

    /**
     * Sources the direction's photos unless it already has them.
     *
     * @return slot → blob path of what is stored for the direction (empty when sourcing is off)
     */
    public Map<String, String> ensurePhotos(String engagementId, String direction, List<String> keywords) {
        List<SourcedPhoto> existing = readManifest(engagementId, direction);
        if (!existing.isEmpty()) return paths(engagementId, direction, existing);
        return fetchAndStore(engagementId, direction, keywords);
    }

    /**
     * Fetches one photo per slot for the direction, never one a sibling direction already uses,
     * and replaces whatever the direction had.
     *
     * @return slot → blob path, e.g. {"cover" → "assets/photos/eng123/EVOLVED/cover.jpg"}
     */
    public Map<String, String> fetchAndStore(String engagementId, String direction, List<String> keywords) {
        if (unsplashAccessKey == null || unsplashAccessKey.isBlank()) {
            log.warn("PhotoSourcingService: UNSPLASH_ACCESS_KEY not configured — skipping photo sourcing");
            return Map.of();
        }
        List<String> terms = searchTerms(keywords);
        if (terms.isEmpty()) {
            log.warn("PhotoSourcingService: no keywords for engagement {} {}", engagementId, direction);
            return Map.of();
        }

        Set<String> used = new HashSet<>();
        for (DirectionBrief.CreativeDirection d : DirectionBrief.CreativeDirection.values()) {
            if (d.name().equals(direction)) continue;
            readManifest(engagementId, d.name()).forEach(p -> used.add(p.id()));
        }

        Map<String, List<Candidate>> pools = new LinkedHashMap<>();
        for (String term : terms) {
            try {
                pools.put(term, new ArrayList<>(search(term)));
            } catch (Exception e) {
                log.warn("PhotoSourcingService: search '{}' failed — {}", term, e.getMessage());
            }
        }
        Map<String, Candidate> picks = assign(SLOTS, terms, pools, used);

        List<SourcedPhoto> stored = new ArrayList<>();
        for (Map.Entry<String, Candidate> e : picks.entrySet()) {
            String slot = e.getKey();
            Candidate c = e.getValue();
            try {
                byte[] bytes = restTemplate.getForObject(c.imageUrl(), byte[].class);
                if (bytes == null || bytes.length == 0) continue;
                blobStorageService.upload(blobPath(engagementId, direction, slot), bytes);
                stored.add(new SourcedPhoto(slot, c.id(), c.photographer(), c.profileUrl()));
                trackDownload(c);
            } catch (Exception ex) {
                log.warn("PhotoSourcingService: photo {} for slot '{}' failed — {}", c.id(), slot, ex.getMessage());
            }
        }
        if (!stored.isEmpty()) {
            writeManifest(engagementId, direction, stored);
            // A slot this fetch could not fill must not keep the photo it replaced.
            Set<String> filled = new HashSet<>();
            stored.forEach(p -> filled.add(p.slot()));
            for (String slot : SLOTS) {
                if (filled.contains(slot)) continue;
                try {
                    blobStorageService.delete(blobPath(engagementId, direction, slot));
                } catch (Exception ex) {
                    log.debug("PhotoSourcingService: stale photo {} not removed — {}", slot, ex.getMessage());
                }
            }
        }
        log.info("PhotoSourcingService: sourced {}/{} photos for engagement {} {}",
            stored.size(), SLOTS.size(), engagementId, direction);
        return paths(engagementId, direction, stored);
    }

    /** The keywords worth searching, most important first: never one asking for packaging or signs. */
    static List<String> searchTerms(List<String> keywords) {
        if (keywords == null) return List.of();
        return keywords.stream()
            .filter(k -> k != null && !k.isBlank()).map(String::strip)
            .filter(k -> !showsBranding(k))
            .distinct().limit(MAX_KEYWORDS).toList();
    }

    /**
     * Gives each slot a photo nobody else uses: slot i draws from keyword i (round robin), then
     * from any keyword with photos left. Full-page slots take a portrait photo when one is left.
     */
    static Map<String, Candidate> assign(List<String> slots, List<String> terms,
                                         Map<String, List<Candidate>> pools, Set<String> used) {
        Set<String> taken = new HashSet<>(used);
        Map<String, Candidate> out = new LinkedHashMap<>();
        for (int i = 0; i < slots.size(); i++) {
            String slot = slots.get(i);
            boolean wantPortrait = FULL_PAGE.contains(slot);
            List<String> order = new ArrayList<>();
            for (int j = 0; j < terms.size(); j++) order.add(terms.get((i + j) % terms.size()));
            Candidate pick = null;
            for (boolean strict : wantPortrait ? new boolean[]{true, false} : new boolean[]{false}) {
                for (String term : order) {
                    pick = takeFirst(pools.getOrDefault(term, List.of()), taken, strict);
                    if (pick != null) break;
                }
                if (pick != null) break;
            }
            if (pick != null) {
                taken.add(pick.id());
                out.put(slot, pick);
            }
        }
        return out;
    }

    private static Candidate takeFirst(List<Candidate> pool, Set<String> taken, boolean portraitOnly) {
        for (Candidate c : pool) {
            if (taken.contains(c.id())) continue;
            if (portraitOnly && !c.portrait()) continue;
            return c;
        }
        return null;
    }

    private List<Candidate> search(String term) throws Exception {
        URI uri = UriComponentsBuilder.fromUriString("https://api.unsplash.com/search/photos")
            .queryParam("query", term)
            .queryParam("per_page", 30)
            .queryParam("content_filter", "high")
            .build().encode().toUri();
        ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers()), String.class);
        if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
            log.warn("Unsplash returned {} for '{}'", response.getStatusCode(), term);
            return List.of();
        }
        List<Candidate> out = new ArrayList<>();
        for (JsonNode r : objectMapper.readTree(response.getBody()).path("results")) {
            // "regular" is ~1080px wide: sharp enough for an A4 screen PDF without being enormous.
            String url = r.path("urls").path("regular").asText("");
            String id = r.path("id").asText("");
            if (url.isBlank() || id.isBlank()) continue;
            if (showsBranding(r.path("alt_description").asText("") + " " + r.path("description").asText(""))) continue;
            out.add(new Candidate(id, url, r.path("links").path("download_location").asText(""),
                r.path("width").asInt(0), r.path("height").asInt(0),
                r.path("user").path("name").asText(""), r.path("user").path("links").path("html").asText("")));
        }
        if (out.isEmpty()) log.warn("No Unsplash results for '{}'", term);
        return out;
    }

    static boolean showsBranding(String description) {
        return BRANDED.matcher(description).find();
    }

    /** Unsplash's API guidelines ask for this ping whenever a photo is used. Best effort. */
    private void trackDownload(Candidate c) {
        if (c.downloadLocation().isBlank()) return;
        try {
            restTemplate.exchange(URI.create(c.downloadLocation()), HttpMethod.GET, new HttpEntity<>(headers()), String.class);
        } catch (Exception e) {
            log.debug("Unsplash download ping for {} failed: {}", c.id(), e.getMessage());
        }
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Client-ID " + unsplashAccessKey);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    static String blobPath(String engagementId, String direction, String slot) {
        return "assets/photos/%s/%s/%s.jpg".formatted(engagementId, direction, slot);
    }

    static String manifestPath(String engagementId, String direction) {
        return "assets/photos/%s/%s/photos.json".formatted(engagementId, direction);
    }

    private static Map<String, String> paths(String engagementId, String direction, List<SourcedPhoto> photos) {
        Map<String, String> out = new LinkedHashMap<>();
        for (SourcedPhoto p : photos) out.put(p.slot(), blobPath(engagementId, direction, p.slot()));
        return out;
    }

    private List<SourcedPhoto> readManifest(String engagementId, String direction) {
        try {
            byte[] bytes = blobStorageService.download(manifestPath(engagementId, direction));
            if (bytes == null || bytes.length == 0) return List.of();
            return Arrays.asList(objectMapper.readValue(bytes, SourcedPhoto[].class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private void writeManifest(String engagementId, String direction, List<SourcedPhoto> photos) {
        try {
            blobStorageService.upload(manifestPath(engagementId, direction), objectMapper.writeValueAsBytes(photos));
        } catch (Exception e) {
            log.warn("PhotoSourcingService: manifest for {} {} not written — {}", engagementId, direction, e.getMessage());
        }
    }
}
