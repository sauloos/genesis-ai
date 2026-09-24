package ai.genesisbrands.service;

import ai.genesisbrands.model.FlowSession;
import ai.genesisbrands.model.FlowSessionEvent;
import ai.genesisbrands.model.PageFlow;
import ai.genesisbrands.model.PageTransition;
import ai.genesisbrands.repository.FlowSessionEventRepository;
import ai.genesisbrands.repository.FlowSessionRepository;
import ai.genesisbrands.repository.PageFlowRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Traverses the PageTransition graph on behalf of a visitor, persisting progress as a
 * FlowSession. Admin-gated harness for this pass (see FlowSessionController) — no
 * public/visitor-facing runtime route yet.
 */
@Service
@RequiredArgsConstructor
public class FlowSessionService {

    private static final int SESSION_HOURS = 24;

    private final FlowSessionRepository sessionRepo;
    private final FlowSessionEventRepository eventRepo;
    private final PageFlowRepository pageFlowRepo;
    private final PageTransitionService pageTransitionService;
    private final ObjectMapper objectMapper;

    @Transactional
    public FlowSession start(String pageFlowId) {
        PageFlow flow = pageFlowRepo.findById(pageFlowId)
            .orElseThrow(() -> new NoSuchElementException("PageFlow not found: " + pageFlowId));
        if (flow.getStartPageId() == null) {
            throw new IllegalArgumentException("PageFlow has no start page configured: " + pageFlowId);
        }
        FlowSession session = new FlowSession();
        session.setToken(UUID.randomUUID().toString());
        session.setPageFlowId(pageFlowId);
        session.setCurrentPageId(flow.getStartPageId());
        session.setExpiresAt(Instant.now().plus(SESSION_HOURS, ChronoUnit.HOURS));
        return sessionRepo.save(session);
    }

    public FlowSession get(String token) {
        return sessionRepo.findByTokenAndExpiresAtAfter(token, Instant.now())
            .orElseThrow(() -> new NoSuchElementException("FlowSession not found or expired: " + token));
    }

    @Transactional
    public FlowSession updateContext(String token, String contextPatchJson) {
        FlowSession session = get(token);
        Map<String, Object> patch;
        try {
            patch = objectMapper.readValue(contextPatchJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("contextPatchJson must be a valid JSON object");
        }
        Map<String, Object> merged;
        try {
            merged = session.getContextJson() == null || session.getContextJson().isBlank()
                ? new java.util.LinkedHashMap<>()
                : new java.util.LinkedHashMap<>(objectMapper.readValue(session.getContextJson(), new TypeReference<Map<String, Object>>() {}));
        } catch (Exception e) {
            merged = new java.util.LinkedHashMap<>();
        }
        merged.putAll(patch);
        try {
            session.setContextJson(objectMapper.writeValueAsString(merged));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize merged context", e);
        }
        session.setUpdatedAt(Instant.now());
        return sessionRepo.save(session);
    }

    @Transactional
    public AdvanceResult advance(String token, String outcomeKey) {
        FlowSession session = get(token);
        if (session.isEnded()) {
            throw new IllegalStateException("FlowSession has already ended: " + token);
        }
        PageTransition transition = pageTransitionService.resolve(session.getCurrentPageId(), outcomeKey)
            .orElseThrow(() -> new IllegalArgumentException(
                "No transition for outcome '" + outcomeKey + "' from page " + session.getCurrentPageId()));

        FlowSessionEvent event = new FlowSessionEvent();
        event.setId(UUID.randomUUID().toString());
        event.setFlowSessionToken(token);
        event.setSourcePageId(session.getCurrentPageId());
        event.setOutcomeKey(outcomeKey);
        event.setTargetKind(transition.getTargetKind());
        event.setTargetPageId(transition.getTargetPageId());
        eventRepo.save(event);

        String redirectToFlowId = null;
        if ("PAGE".equals(transition.getTargetKind())) {
            session.setCurrentPageId(transition.getTargetPageId());
        } else {
            PageFlow flow = pageFlowRepo.findById(session.getPageFlowId())
                .orElseThrow(() -> new NoSuchElementException("PageFlow not found: " + session.getPageFlowId()));
            String endAction = flow.getEndAction();
            session.setEnded(true);
            if ("END_PAGE".equals(endAction)) {
                session.setCurrentPageId(flow.getEndPageId());
            } else if ("REDIRECT_FLOW".equals(endAction)) {
                session.setCurrentPageId(null);
                redirectToFlowId = flow.getEndTargetFlowId();
            } else {
                session.setCurrentPageId(null);
            }
        }
        session.setUpdatedAt(Instant.now());
        return new AdvanceResult(sessionRepo.save(session), redirectToFlowId);
    }

    public List<FlowSessionEvent> history(String token) {
        get(token); // validate exists and not expired
        return eventRepo.findByFlowSessionTokenOrderByOccurredAtAsc(token);
    }

    public record AdvanceResult(FlowSession session, String redirectToFlowId) {}
}
