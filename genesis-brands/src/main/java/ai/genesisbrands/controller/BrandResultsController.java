package ai.genesisbrands.controller;

import ai.genesisbrands.model.Engagement;
import ai.genesisbrands.model.FlowSession;
import ai.genesisbrands.model.PageFlow;
import ai.genesisbrands.repository.EngagementRepository;
import ai.genesisbrands.repository.PageFlowRepository;
import ai.genesisbrands.service.FlowEngagementService;
import ai.genesisbrands.service.FlowSessionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Public endpoint the brandResults widget calls to kick off (or resume) the brand
 * direction pipeline for its FlowSession. The pipeline run itself, polling, and preview
 * images are served by FlowEngagementService/EngagementController — this controller
 * only owns the idempotent "start it once" handshake, keyed by widgetId (in case a page
 * ever hosts more than one brandResults widget) and guarded by a row lock on the
 * session so concurrent requests (double-click, reload racing the first click) can't
 * trigger two pipeline runs for the same widget.
 */
@RestController
@RequestMapping("/api/public/brand-results")
@RequiredArgsConstructor
public class BrandResultsController {

    private static final Logger log = LoggerFactory.getLogger(BrandResultsController.class);

    private final FlowSessionService flowSessionService;
    private final PageFlowRepository pageFlowRepo;
    private final FlowEngagementService flowEngagementService;
    private final EngagementRepository engagementRepo;
    private final ObjectMapper objectMapper;

    @Value("${genesis.flow.mock-brand-results:false}")
    private boolean mockBrandResults;

    @Transactional
    @PostMapping("/flow-sessions/{token}/widgets/{widgetId}/start")
    public StartResponse start(@PathVariable String token, @PathVariable String widgetId,
                                @RequestParam(defaultValue = "false") boolean retry) {
        FlowSession session;
        try {
            session = flowSessionService.getForUpdate(token);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        if (session.getClientUserId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sign in required before generating brand results");
        }

        String contextKey = "__engagement:" + widgetId;
        Object existing = parseContext(session.getContextJson()).get(contextKey);

        if (existing != null) {
            Engagement e = engagementRepo.findById(String.valueOf(existing)).orElse(null);
            boolean stuckEmpty = e != null && e.getStatus() == Engagement.Status.DONE && hasNoDirections(e);
            boolean retryAllowed = retry && e != null
                && (e.getStatus() == Engagement.Status.FAILED || stuckEmpty);
            if (!retryAllowed) {
                return new StartResponse(String.valueOf(existing), e != null ? e.getStatus().name() : "UNKNOWN");
            }
        }

        String engagementId;
        Engagement.Status status;
        if (mockBrandResults) {
            Engagement mocked = engagementRepo.findAllByStatusOrderByCreatedAtDesc(Engagement.Status.DONE).stream()
                .filter(e -> !hasNoDirections(e))
                .findFirst()
                .orElse(null);
            if (mocked != null) {
                // Clone the mocked engagement's results into a fresh row owned by this
                // session, rather than reusing the same shared row — so owner-gated
                // actions (choose-direction, preview) work normally through the existing
                // ownership check with no change to auth logic. Testing/demo aid only;
                // each session that hits this path gets its own throwaway copy.
                Engagement clone = new Engagement();
                clone.setId(java.util.UUID.randomUUID().toString());
                clone.setSource(Engagement.Source.CLIENT);
                clone.setStatus(Engagement.Status.DONE);
                clone.setEvalMode(mocked.isEvalMode());
                clone.setBriefsJson(mocked.getBriefsJson());
                clone.setResultsJson(mocked.getResultsJson());
                clone.setClientUserId(session.getClientUserId());
                engagementRepo.save(clone);
                log.info("genesis.flow.mock-brand-results is on — cloned engagement {} as {} for widget {} instead of running the pipeline", mocked.getId(), clone.getId(), widgetId);
                engagementId = clone.getId();
                status = Engagement.Status.DONE;
            } else {
                log.warn("genesis.flow.mock-brand-results is on but no DONE engagement exists to reuse — running the real pipeline for widget {}", widgetId);
                engagementId = triggerRealEngagement(session);
                status = Engagement.Status.PENDING;
            }
        } else {
            engagementId = triggerRealEngagement(session);
            status = Engagement.Status.PENDING;
        }

        try {
            flowSessionService.updateContext(token, objectMapper.writeValueAsString(Map.of(contextKey, engagementId)));
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to persist engagement id onto session");
        }

        return new StartResponse(engagementId, status.name());
    }

    /** A DONE engagement whose pipeline run produced zero directions (e.g. brief derivation
     *  failed silently) is functionally broken — treat it the same as FAILED for retry/mock
     *  reuse purposes rather than letting the widget get permanently stuck on it. */
    private boolean hasNoDirections(Engagement e) {
        if (e.getResultsJson() == null) return true;
        try {
            return objectMapper.readTree(e.getResultsJson()).path("directions").isEmpty();
        } catch (Exception ex) {
            return true;
        }
    }

    private String triggerRealEngagement(FlowSession session) {
        PageFlow flow = pageFlowRepo.findById(session.getPageFlowId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PageFlow not found: " + session.getPageFlowId()));
        return flowEngagementService.triggerEngagement(flow, session);
    }

    private Map<String, Object> parseContext(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {}));
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    public record StartResponse(String engagementId, String status) {}
}
