package ai.genesisbrands.service;

import ai.genesisbrands.model.Page;
import ai.genesisbrands.model.PageTransition;
import ai.genesisbrands.model.PageWidget;
import ai.genesisbrands.platform.WidgetDescriptor;
import ai.genesisbrands.platform.WidgetOutcome;
import ai.genesisbrands.repository.PageRepository;
import ai.genesisbrands.repository.PageTransitionRepository;
import ai.genesisbrands.repository.PageWidgetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the generic (sourcePageId, outcomeKey) -> target edge model that replaced the
 * old fixed nextPageId/errorPageId/endsFlow columns on Page. A page's set of possible
 * outcomes is derived from its placed widgets' WidgetDescriptor.outcomes(), plus the
 * always-present reserved "error" outcome.
 */
@Service
@RequiredArgsConstructor
public class PageTransitionService {

    public static final String ERROR_OUTCOME = "error";

    private final PageTransitionRepository transitionRepo;
    private final PageRepository pageRepo;
    private final PageWidgetRepository pageWidgetRepo;
    private final List<WidgetDescriptor> widgetDescriptors;

    public List<PageTransition> listByFlow(String pageFlowId) {
        return transitionRepo.findByPageFlowId(pageFlowId);
    }

    /** Distinct outcome ports for a page: union (by key, first-seen wins) of outcomes()
     *  from each distinct widget placed on the page, ordered by (slotKey, orderInSlot);
     *  falls back to [WidgetOutcome.DEFAULT] when the page has no widgets; the reserved
     *  "error" outcome is always appended last. */
    public List<WidgetOutcome> outcomesForPage(Page page) {
        List<PageWidget> widgets = pageWidgetRepo.findByPageIdOrderByOrderInSlotAsc(page.getId()).stream()
            .sorted(Comparator.comparing(PageWidget::getSlotKey).thenComparing(PageWidget::getOrderInSlot))
            .toList();

        Map<String, WidgetOutcome> byKey = new LinkedHashMap<>();
        for (PageWidget widget : widgets) {
            widgetDescriptors.stream()
                .filter(d -> d.widgetType().equals(widget.getWidgetType()))
                .findFirst()
                .ifPresent(d -> {
                    for (WidgetOutcome o : d.outcomes()) {
                        byKey.putIfAbsent(o.key(), o);
                    }
                });
        }
        if (byKey.isEmpty()) {
            byKey.put(WidgetOutcome.DEFAULT.key(), WidgetOutcome.DEFAULT);
        }
        byKey.remove(ERROR_OUTCOME);
        byKey.put(ERROR_OUTCOME, new WidgetOutcome(ERROR_OUTCOME, "Error"));
        return List.copyOf(byKey.values());
    }

    @Transactional
    public PageTransition setTransition(String sourcePageId, String outcomeKey, String targetKind, String targetPageId) {
        Page source = pageRepo.findById(sourcePageId)
            .orElseThrow(() -> new IllegalArgumentException("Page not found: " + sourcePageId));

        boolean validOutcome = ERROR_OUTCOME.equals(outcomeKey)
            || outcomesForPage(source).stream().anyMatch(o -> o.key().equals(outcomeKey));
        if (!validOutcome) {
            throw new IllegalArgumentException("Outcome '" + outcomeKey + "' is not valid for page: " + sourcePageId);
        }

        if ("FLOW_END".equals(targetKind)) {
            if (targetPageId != null) {
                throw new IllegalArgumentException("targetPageId must be null when targetKind is FLOW_END");
            }
        } else if ("PAGE".equals(targetKind)) {
            Page target = pageRepo.findById(targetPageId)
                .orElseThrow(() -> new IllegalArgumentException("Target page not found: " + targetPageId));
            if (!target.getPageFlowId().equals(source.getPageFlowId())) {
                throw new IllegalArgumentException("Target page must belong to the same PageFlow: " + targetPageId);
            }
        } else {
            throw new IllegalArgumentException("Unknown targetKind: " + targetKind);
        }

        PageTransition transition = transitionRepo.findBySourcePageIdAndOutcomeKey(sourcePageId, outcomeKey)
            .orElseGet(() -> {
                PageTransition t = new PageTransition();
                t.setId(UUID.randomUUID().toString());
                t.setPageFlowId(source.getPageFlowId());
                t.setSourcePageId(sourcePageId);
                t.setOutcomeKey(outcomeKey);
                return t;
            });
        transition.setTargetKind(targetKind);
        transition.setTargetPageId(targetPageId);
        transition.setUpdatedAt(Instant.now());
        return transitionRepo.save(transition);
    }

    @Transactional
    public void clearTransition(String sourcePageId, String outcomeKey) {
        transitionRepo.findBySourcePageIdAndOutcomeKey(sourcePageId, outcomeKey)
            .ifPresent(transitionRepo::delete);
    }

    /** Explicit row if present; else, only for the "error" outcome, the flow's singleton
     *  designated error page (excluding the source page itself), synthesized (not persisted). */
    public Optional<PageTransition> resolve(String pageId, String outcomeKey) {
        Optional<PageTransition> explicit = transitionRepo.findBySourcePageIdAndOutcomeKey(pageId, outcomeKey);
        if (explicit.isPresent() || !ERROR_OUTCOME.equals(outcomeKey)) {
            return explicit;
        }
        Page page = pageRepo.findById(pageId).orElse(null);
        if (page == null) {
            return Optional.empty();
        }
        return pageRepo.findByPageFlowId(page.getPageFlowId()).stream()
            .filter(Page::isErrorPage)
            .filter(p -> !p.getId().equals(pageId))
            .min(Comparator.comparing(Page::getCreatedAt))
            .map(errorPage -> {
                PageTransition synthesized = new PageTransition();
                synthesized.setPageFlowId(page.getPageFlowId());
                synthesized.setSourcePageId(pageId);
                synthesized.setOutcomeKey(ERROR_OUTCOME);
                synthesized.setTargetKind("PAGE");
                synthesized.setTargetPageId(errorPage.getId());
                return synthesized;
            });
    }

    @Transactional
    public void deleteByFlow(String pageFlowId) {
        transitionRepo.deleteByPageFlowId(pageFlowId);
    }

    @Transactional
    public void deleteForPage(String pageId) {
        transitionRepo.deleteBySourcePageId(pageId);
        transitionRepo.deleteByTargetPageId(pageId);
    }
}
