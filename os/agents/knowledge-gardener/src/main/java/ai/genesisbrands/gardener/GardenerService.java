package ai.genesisbrands.gardener;

import ai.genesisbrands.model.IngestionEvent;
import ai.genesisbrands.platform.IngestionEventListener;
import ai.genesisbrands.repository.IngestionEventRepository;
import ai.genesisbrands.service.GenericSingleShotAgentService;
import ai.genesisbrands.service.Layer1Service;
import ai.genesisbrands.usage.UsageContext;
import ai.genesisbrands.usage.UsageScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reviews Layer 1 sources against the Layer 1 modules. Each successful Layer 1 ingestion queues a
 * review of the sources it added (when the admin has enabled the gardener); an admin can also ask
 * for one source or event by hand. Reviews run one at a time on the gardener's own thread, and
 * each model call is metered to the gardener in the usage ledger.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GardenerService implements IngestionEventListener {

    public record Status(boolean enabled, int maxSourcesPerEvent, int modules, long openFindings, int queued,
                         List<RecentEvent> recentEvents) {}

    public record RecentEvent(String id, String label, String status, int itemsIngested, Instant startedAt) {}

    public record SettingsUpdate(Boolean enabled, Integer maxSourcesPerEvent) {}

    private final GardenerSettingsRepository settingsRepo;
    private final GardenerReviewRepository reviews;
    private final GardenerFindingRepository findings;
    private final IngestionEventRepository events;
    private final GardenerSources sources;
    private final Layer1Service layer1;
    private final GenericSingleShotAgentService models;
    private final ObjectMapper json;

    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "knowledge-gardener");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger queued = new AtomicInteger();

    @PreDestroy
    void stop() {
        pool.shutdownNow();
    }

    @Override
    public void finished(IngestionEvent event) {
        if (!"layer1".equals(event.getLayer()) || event.getItemsIngested() <= 0) return;
        if (event.getStatus() == IngestionEvent.Status.FAILED || event.getStatus() == IngestionEvent.Status.RUNNING) return;
        if (!settings().isEnabled()) return;
        submit(() -> reviewEvent(event.getId(), false));
    }

    public GardenerSettings settings() {
        return settingsRepo.findById(GardenerSettings.ID).orElseGet(GardenerSettings::new);
    }

    public GardenerSettings updateSettings(SettingsUpdate update) {
        GardenerSettings s = settings();
        if (update.enabled() != null) s.setEnabled(update.enabled());
        if (update.maxSourcesPerEvent() != null) {
            if (update.maxSourcesPerEvent() < 1 || update.maxSourcesPerEvent() > 100) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sources per ingestion must be between 1 and 100.");
            }
            s.setMaxSourcesPerEvent(update.maxSourcesPerEvent());
        }
        return settingsRepo.save(s);
    }

    public Status status() {
        GardenerSettings s = settings();
        List<RecentEvent> recent = events.findTop20ByOrderByStartedAtDesc().stream()
            .filter(e -> "layer1".equals(e.getLayer()))
            .map(e -> new RecentEvent(e.getId(), e.getSourceLabel(), e.getStatus().name(), e.getItemsIngested(), e.getStartedAt()))
            .toList();
        return new Status(s.isEnabled(), s.getMaxSourcesPerEvent(), layer1.listModules().size(),
            findings.countByStatus(GardenerFinding.OPEN), queued.get(), recent);
    }

    /** An admin's "Review now": an event's sources (again), or one source by id. Works with the gardener off. */
    public Map<String, String> reviewNow(String eventId, String sourceId) {
        if (sourceId != null && !sourceId.isBlank()) {
            submit(() -> sources.find(sourceId).ifPresentOrElse(
                src -> reviewSource(src, null),
                () -> log.info("Gardener: source {} is not in the knowledge store", sourceId)));
            return Map.of("status", "queued");
        }
        if (eventId == null || eventId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name an ingestion event or a source.");
        IngestionEvent event = events.findById(eventId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "That ingestion event does not exist."));
        if (!"layer1".equals(event.getLayer())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The gardener reviews Layer 1 ingestions only.");
        submit(() -> reviewEvent(event.getId(), true));
        return Map.of("status", "queued");
    }

    public List<GardenerFinding> findings(String status, int limit) {
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, 500)));
        return status == null || status.isBlank() || status.equals("ALL")
            ? findings.findAllByOrderByCreatedAtDesc(page)
            : findings.findAllByStatusOrderByCreatedAtDesc(status, page);
    }

    public List<GardenerReview> reviews(int limit) {
        return reviews.findAllByOrderByReviewedAtDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 500))));
    }

    public GardenerFinding resolve(String id, String status, String note) {
        if (!GardenerFinding.APPLIED.equals(status) && !GardenerFinding.DISMISSED.equals(status) && !GardenerFinding.OPEN.equals(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status.");
        }
        GardenerFinding f = findings.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "That finding does not exist."));
        f.setStatus(status);
        f.setNote(note == null || note.isBlank() ? null : note.strip());
        f.setResolvedAt(GardenerFinding.OPEN.equals(status) ? null : Instant.now());
        return findings.save(f);
    }

    private void submit(Runnable work) {
        queued.incrementAndGet();
        pool.submit(() -> {
            try {
                work.run();
            } catch (RuntimeException e) {
                log.warn("Gardener review failed: {}", e.getMessage());
            } finally {
                queued.decrementAndGet();
            }
        });
    }

    private void reviewEvent(String eventId, boolean again) {
        IngestionEvent event = events.findById(eventId).orElse(null);
        if (event == null) return;
        int max = settings().getMaxSourcesPerEvent();
        List<GardenerSources.Source> added = sources.ingestedSince(event.getLayer(), event.getStartedAt());
        int done = 0;
        for (GardenerSources.Source src : added) {
            if (done >= max) break;
            if (!again && reviews.existsBySourceIdAndStatus(src.sourceId(), GardenerReview.REVIEWED)) continue;
            reviewSource(src, eventId);
            done++;
        }
        log.info("Gardener reviewed {} of {} new source(s) from ingestion {}", done, added.size(), eventId);
    }

    void reviewSource(GardenerSources.Source src, String eventId) {
        GardenerReview review = new GardenerReview();
        review.setId(UUID.randomUUID().toString());
        review.setEventId(eventId);
        review.setSourceId(src.sourceId());
        review.setTitle(src.title());
        review.setSourceUrl(src.url());
        try {
            String text = sources.text(src.sourceId(), GardenerPrompt.MAX_SOURCE_CHARS);
            if (text.isBlank()) throw new IllegalStateException("The source has no text in the knowledge store.");
            Map<String, String> modules = layer1.listModules();
            String prompt = GardenerPrompt.build(modules, src.title(), src.url(), text);
            String reply = UsageContext.callWith(UsageScope.of(null, KnowledgeGardenerAgent.ID, "gardener", review.getId()),
                () -> models.generate(KnowledgeGardenerAgent.ID, prompt, null));
            GardenerPrompt.Review parsed = GardenerPrompt.parse(json, reply, modules.keySet());
            Instant now = Instant.now();
            for (GardenerPrompt.Finding f : parsed.findings()) {
                GardenerFinding row = new GardenerFinding();
                row.setId(UUID.randomUUID().toString());
                row.setReviewId(review.getId());
                row.setSourceId(src.sourceId());
                row.setSourceTitle(src.title());
                row.setSourceUrl(src.url());
                row.setKind(f.kind());
                row.setModule(f.module());
                row.setModuleExcerpt(f.moduleExcerpt());
                row.setSourceExcerpt(f.sourceExcerpt());
                row.setExplanation(f.explanation());
                row.setSuggestion(f.suggestion());
                row.setCreatedAt(now);
                findings.save(row);
            }
            review.setStatus(GardenerReview.REVIEWED);
            review.setSummary(parsed.summary());
            review.setFindings(parsed.findings().size());
        } catch (RuntimeException e) {
            review.setStatus(GardenerReview.FAILED);
            review.setError(e.getMessage());
            log.warn("Gardener could not review source {}: {}", src.sourceId(), e.getMessage());
        }
        review.setReviewedAt(Instant.now());
        reviews.save(review);
    }
}
