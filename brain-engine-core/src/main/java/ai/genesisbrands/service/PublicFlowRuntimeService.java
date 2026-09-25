package ai.genesisbrands.service;

import ai.genesisbrands.model.FlowSession;
import ai.genesisbrands.model.Page;
import ai.genesisbrands.model.PageFlow;
import ai.genesisbrands.model.PageWidget;
import ai.genesisbrands.platform.WidgetOutcome;
import ai.genesisbrands.repository.PageFlowRepository;
import ai.genesisbrands.repository.PageRepository;
import ai.genesisbrands.repository.PageWidgetRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Resolves a live PageFlow by slug and drives FlowSession traversal on behalf of a
 * real, anonymous site visitor — the public counterpart to the admin-gated
 * FlowSessionController/Simulate panel. Wraps FlowSessionService and
 * PageTransitionService rather than modifying either, so their existing test suites
 * stay untouched. Page data is only ever returned embedded in a session response for
 * the session's actual current page — never as a standalone "fetch any page" lookup —
 * so a visitor can't probe pages unreachable from their own session state.
 */
@Service
@RequiredArgsConstructor
public class PublicFlowRuntimeService {

    private final PageFlowRepository pageFlowRepo;
    private final PageRepository pageRepo;
    private final PageWidgetRepository pageWidgetRepo;
    private final PageTransitionService pageTransitionService;
    private final FlowSessionService flowSessionService;
    private final ObjectMapper objectMapper;

    public PublicSessionView start(String slug, String rootPrefix, String clientUserId) {
        PageFlow flow = pageFlowRepo.findLiveByRoute(rootPrefix, slug)
            .orElseThrow(() -> new NoSuchElementException("No live PageFlow for slug: " + slug));
        FlowSession session = flowSessionService.start(flow.getId(), false);
        flowSessionService.linkClientUser(session.getToken(), clientUserId);
        return toView(session, flow, null);
    }

    /** Admin Simulate preview: resolves by id instead of a live slug (a draft flow has
     *  no live route yet), and marks the session simulated so it renders through the
     *  same view shape a real visitor gets while skipping real side effects. */
    public PublicSessionView startSimulated(String pageFlowId) {
        PageFlow flow = pageFlowRepo.findById(pageFlowId)
            .orElseThrow(() -> new NoSuchElementException("PageFlow not found: " + pageFlowId));
        FlowSession session = flowSessionService.start(pageFlowId, true);
        return toView(session, flow, null);
    }

    public PublicSessionView resume(String token, String clientUserId) {
        flowSessionService.linkClientUser(token, clientUserId);
        return toView(flowSessionService.get(token));
    }

    public PublicSessionView updateContext(String token, String contextPatchJson, String clientUserId) {
        flowSessionService.linkClientUser(token, clientUserId);
        return toView(flowSessionService.updateContext(token, contextPatchJson));
    }

    /**
     * A single visitor click can resolve to at most one flow-to-flow handoff:
     * FlowSessionService.advance() only ever sets AdvanceResult.redirectToFlowId when
     * the *current* advance() call resolves to FLOW_END with endAction=REDIRECT_FLOW,
     * and FlowSessionService.start() (used to create the session on the target flow)
     * never itself produces a further redirect signal — reaching a second hop requires
     * a second visitor-triggered advance() on the new flow. So there is no chain to
     * cap here; this folds that one possible hop into a single round trip so the
     * visitor's browser sees one request instead of two. The whole operation is
     * transactional so a failure on the second flow's start() (e.g. it has no
     * startPageId configured) can't strand the first flow's session in an ended state
     * with nothing to resume into.
     */
    @Transactional
    public PublicSessionView advance(String token, String outcomeKey, String clientUserId) {
        flowSessionService.linkClientUser(token, clientUserId);
        FlowSessionService.AdvanceResult result = flowSessionService.advance(token, outcomeKey);
        if (result.redirectToFlowId() != null) {
            FlowSession redirected = flowSessionService.start(result.redirectToFlowId(), result.session().isSimulated());
            flowSessionService.linkClientUser(redirected.getToken(), clientUserId);
            return toView(redirected);
        }
        PageFlow flow = pageFlowRepo.findById(result.session().getPageFlowId())
            .orElseThrow(() -> new NoSuchElementException("PageFlow not found: " + result.session().getPageFlowId()));
        return toView(result.session(), flow, result.engagementId());
    }

    private PublicSessionView toView(FlowSession session) {
        PageFlow flow = pageFlowRepo.findById(session.getPageFlowId())
            .orElseThrow(() -> new NoSuchElementException("PageFlow not found: " + session.getPageFlowId()));
        return toView(session, flow, null);
    }

    private PublicSessionView toView(FlowSession session, PageFlow flow, String engagementId) {
        // An ended session still has a page to render when the flow's endAction is
        // END_PAGE — advance() points currentPageId at that designated end page rather
        // than clearing it (REDIRECT_FLOW/CREATE_ENGAGEMENT/unconfigured clear it to
        // null instead), so only a null currentPageId means "no page to show".
        boolean hasEndPage = session.isEnded() && session.getCurrentPageId() != null;
        PageRenderView page = (!session.isEnded() || hasEndPage) ? renderCurrentPage(session) : null;
        return new PublicSessionView(session.getToken(), flow.getSlug(), flow.getLivePath(), page, session.isEnded(), engagementId, session.isSimulated());
    }

    private PageRenderView renderCurrentPage(FlowSession session) {
        Page page = pageRepo.findById(session.getCurrentPageId())
            .orElseThrow(() -> new NoSuchElementException("Page not found: " + session.getCurrentPageId()));
        if (page.isRequiresAuth() && session.getClientUserId() == null) {
            throw new AuthRequiredException("Page requires authentication: " + page.getId());
        }
        List<PageWidget> widgets = pageWidgetRepo.findByPageIdOrderByOrderInSlotAsc(page.getId());

        PageWidget redirectWidget = widgets.stream()
            .filter(w -> "redirect".equals(w.getWidgetType()))
            .findFirst()
            .orElse(null);
        String redirectUrl = redirectWidget == null ? null
            : String.valueOf(parseConfig(redirectWidget.getConfigJson()).getOrDefault("targetUrl", ""));

        boolean soleRedirect = redirectWidget != null && widgets.size() == 1;
        if (soleRedirect) {
            return new PageRenderView(page.getId(), page.getName(), page.getLayoutKey(), page.isBordered(), List.of(), List.of(), redirectUrl);
        }

        // A redirect widget mixed with others has no defined visual (WidgetDescriptor
        // has no render semantics for it), so it's excluded from the rendered widget
        // list, but its target is still surfaced as redirectUrl so the client can show
        // the rest of the page briefly before honoring the redirect.
        List<PageWidgetView> widgetViews = widgets.stream()
            .filter(w -> !"redirect".equals(w.getWidgetType()))
            .map(w -> new PageWidgetView(w.getId(), w.getSlotKey(), w.getOrderInSlot(), w.getWidgetType(), parseConfig(w.getConfigJson())))
            .toList();

        List<WidgetOutcome> outcomes = pageTransitionService.outcomesForPage(page).stream()
            .filter(o -> !PageTransitionService.ERROR_OUTCOME.equals(o.key()))
            .toList();

        return new PageRenderView(page.getId(), page.getName(), page.getLayoutKey(), page.isBordered(), widgetViews, outcomes, redirectUrl);
    }

    private Map<String, Object> parseConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(configJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    public record PublicSessionView(String token, String slug, String livePath, PageRenderView page, boolean ended, String engagementId, boolean simulated) {}

    public record PageRenderView(
        String pageId, String name, String layoutKey, boolean bordered,
        List<PageWidgetView> widgets, List<WidgetOutcome> outcomes, String redirectUrl
    ) {}

    public record PageWidgetView(String id, String slotKey, int orderInSlot, String widgetType, Map<String, Object> config) {}
}
