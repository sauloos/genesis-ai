package ai.genesisbrands.controller;

import ai.genesisbrands.model.FlowSession;
import ai.genesisbrands.model.PageFlow;
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
import java.util.Optional;

/**
 * Public endpoint the brandResults widget calls to kick off (or resume) the brand
 * direction pipeline for its FlowSession. The pipeline run itself, polling, and preview
 * images are served by the app's engagement endpoints — this controller
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
    private final BrandResultsEngagements engagements;
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
            Optional<BrandResultsEngagements.State> state = engagements.find(String.valueOf(existing));
            if (!(retry && state.map(BrandResultsEngagements.State::retryable).orElse(false))) {
                return new StartResponse(String.valueOf(existing), state.map(BrandResultsEngagements.State::status).orElse("UNKNOWN"));
            }
        }

        String engagementId = null;
        String status = "DONE";
        if (mockBrandResults) {
            // Testing/demo aid: each session that hits this path gets its own throwaway copy.
            engagementId = engagements.cloneLatestFinishedFor(session.getClientUserId()).orElse(null);
            if (engagementId != null) {
                log.info("genesis.flow.mock-brand-results is on — reused a finished engagement as {} for widget {} instead of running the pipeline", engagementId, widgetId);
            } else {
                log.warn("genesis.flow.mock-brand-results is on but no DONE engagement exists to reuse — running the real pipeline for widget {}", widgetId);
            }
        }
        if (engagementId == null) {
            engagementId = triggerRealEngagement(session);
            status = "PENDING";
        }

        try {
            flowSessionService.updateContext(token, objectMapper.writeValueAsString(Map.of(contextKey, engagementId)));
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to persist engagement id onto session");
        }

        return new StartResponse(engagementId, status);
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
