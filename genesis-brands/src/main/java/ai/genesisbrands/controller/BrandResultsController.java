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

    private final FlowSessionService flowSessionService;
    private final PageFlowRepository pageFlowRepo;
    private final FlowEngagementService flowEngagementService;
    private final EngagementRepository engagementRepo;
    private final ObjectMapper objectMapper;

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
            boolean retryAllowed = retry && e != null && e.getStatus() == Engagement.Status.FAILED;
            if (!retryAllowed) {
                return new StartResponse(String.valueOf(existing), e != null ? e.getStatus().name() : "UNKNOWN");
            }
        }

        PageFlow flow = pageFlowRepo.findById(session.getPageFlowId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PageFlow not found: " + session.getPageFlowId()));
        String engagementId = flowEngagementService.triggerEngagement(flow, session);

        try {
            flowSessionService.updateContext(token, objectMapper.writeValueAsString(Map.of(contextKey, engagementId)));
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to persist engagement id onto session");
        }

        return new StartResponse(engagementId, Engagement.Status.PENDING.name());
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
