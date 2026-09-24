package ai.genesisbrands.service;

import ai.genesisbrands.model.FlowSession;
import ai.genesisbrands.model.Page;
import ai.genesisbrands.model.PageFlow;
import ai.genesisbrands.model.PageWidget;
import ai.genesisbrands.platform.WidgetOutcome;
import ai.genesisbrands.repository.PageFlowRepository;
import ai.genesisbrands.repository.PageRepository;
import ai.genesisbrands.repository.PageWidgetRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicFlowRuntimeServiceTest {

    @Mock private PageFlowRepository pageFlowRepo;
    @Mock private PageRepository pageRepo;
    @Mock private PageWidgetRepository pageWidgetRepo;
    @Mock private PageTransitionService pageTransitionService;
    @Mock private FlowSessionService flowSessionService;

    private PublicFlowRuntimeService service;

    @BeforeEach
    void setUp() {
        service = new PublicFlowRuntimeService(pageFlowRepo, pageRepo, pageWidgetRepo,
            pageTransitionService, flowSessionService, new ObjectMapper());
    }

    private PageFlow flow(String id, String slug) {
        PageFlow f = new PageFlow();
        f.setId(id);
        f.setSlug(slug);
        return f;
    }

    private Page page(String id, String flowId) {
        Page p = new Page();
        p.setId(id);
        p.setPageFlowId(flowId);
        p.setName("Page " + id);
        p.setLayoutKey("SINGLE_COLUMN");
        return p;
    }

    private PageWidget widget(String pageId, String slotKey, int order, String widgetType, String configJson) {
        PageWidget w = new PageWidget();
        w.setId("w-" + pageId + "-" + slotKey + "-" + order);
        w.setPageId(pageId);
        w.setSlotKey(slotKey);
        w.setOrderInSlot(order);
        w.setWidgetType(widgetType);
        w.setConfigJson(configJson);
        return w;
    }

    private FlowSession session(String token, String flowId, String currentPageId, boolean ended) {
        FlowSession s = new FlowSession();
        s.setToken(token);
        s.setPageFlowId(flowId);
        s.setCurrentPageId(currentPageId);
        s.setEnded(ended);
        s.setExpiresAt(Instant.now().plusSeconds(3600));
        return s;
    }

    @Test
    void start_unknownOrInactiveSlug_throwsNoSuchElement() {
        when(pageFlowRepo.findLiveByRoute(null, "ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.start("ghost", null)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void start_liveFlow_createsSessionAndReturnsRenderView() {
        PageFlow f = flow("f1", "verify-runtime");
        when(pageFlowRepo.findLiveByRoute(null, "verify-runtime")).thenReturn(Optional.of(f));
        FlowSession s = session("tok", "f1", "p1", false);
        when(flowSessionService.start("f1")).thenReturn(s);
        Page p = page("p1", "f1");
        when(pageRepo.findById("p1")).thenReturn(Optional.of(p));
        when(pageWidgetRepo.findByPageIdOrderByOrderInSlotAsc("p1")).thenReturn(List.of());
        when(pageTransitionService.outcomesForPage(p))
            .thenReturn(List.of(WidgetOutcome.DEFAULT, new WidgetOutcome("error", "Error")));

        PublicFlowRuntimeService.PublicSessionView view = service.start("verify-runtime", null);

        assertThat(view.token()).isEqualTo("tok");
        assertThat(view.slug()).isEqualTo("verify-runtime");
        assertThat(view.livePath()).isEqualTo("/live/verify-runtime");
        assertThat(view.ended()).isFalse();
        assertThat(view.page().pageId()).isEqualTo("p1");
        assertThat(view.page().outcomes()).extracting(WidgetOutcome::key).containsExactly("next");
    }

    @Test
    void renderCurrentPage_contentWidget_returnsConfigAndOutcomes_excludingError() {
        PageFlow f = flow("f1", "s");
        FlowSession s = session("tok", "f1", "p1", false);
        when(flowSessionService.get("tok")).thenReturn(s);
        when(pageFlowRepo.findById("f1")).thenReturn(Optional.of(f));
        Page p = page("p1", "f1");
        when(pageRepo.findById("p1")).thenReturn(Optional.of(p));
        when(pageWidgetRepo.findByPageIdOrderByOrderInSlotAsc("p1"))
            .thenReturn(List.of(widget("p1", "main", 0, "content", "{\"heading\":\"Hi\",\"body\":\"Welcome\"}")));
        when(pageTransitionService.outcomesForPage(p))
            .thenReturn(List.of(WidgetOutcome.DEFAULT, new WidgetOutcome("error", "Error")));

        PublicFlowRuntimeService.PublicSessionView view = service.resume("tok");

        assertThat(view.page().widgets()).hasSize(1);
        assertThat(view.page().widgets().get(0).config()).containsEntry("heading", "Hi");
        assertThat(view.page().outcomes()).extracting(WidgetOutcome::key).containsExactly("next");
        assertThat(view.page().redirectUrl()).isNull();
    }

    @Test
    void renderCurrentPage_soleRedirectWidget_setsRedirectUrl_emptyWidgetList() {
        PageFlow f = flow("f1", "s");
        FlowSession s = session("tok", "f1", "p1", false);
        when(flowSessionService.get("tok")).thenReturn(s);
        when(pageFlowRepo.findById("f1")).thenReturn(Optional.of(f));
        Page p = page("p1", "f1");
        when(pageRepo.findById("p1")).thenReturn(Optional.of(p));
        when(pageWidgetRepo.findByPageIdOrderByOrderInSlotAsc("p1"))
            .thenReturn(List.of(widget("p1", "main", 0, "redirect", "{\"targetUrl\":\"/discover\"}")));

        PublicFlowRuntimeService.PublicSessionView view = service.resume("tok");

        assertThat(view.page().redirectUrl()).isEqualTo("/discover");
        assertThat(view.page().widgets()).isEmpty();
    }

    @Test
    void renderCurrentPage_mixedRedirectWidget_omitsWidgetFromList_butStillSetsRedirectUrl() {
        PageFlow f = flow("f1", "s");
        FlowSession s = session("tok", "f1", "p1", false);
        when(flowSessionService.get("tok")).thenReturn(s);
        when(pageFlowRepo.findById("f1")).thenReturn(Optional.of(f));
        Page p = page("p1", "f1");
        when(pageRepo.findById("p1")).thenReturn(Optional.of(p));
        when(pageWidgetRepo.findByPageIdOrderByOrderInSlotAsc("p1")).thenReturn(List.of(
            widget("p1", "main", 0, "content", "{\"heading\":\"Hi\"}"),
            widget("p1", "main", 1, "redirect", "{\"targetUrl\":\"/discover\"}")
        ));
        when(pageTransitionService.outcomesForPage(p)).thenReturn(List.of(WidgetOutcome.DEFAULT));

        PublicFlowRuntimeService.PublicSessionView view = service.resume("tok");

        assertThat(view.page().redirectUrl()).isEqualTo("/discover");
        assertThat(view.page().widgets()).extracting(PublicFlowRuntimeService.PageWidgetView::widgetType)
            .containsExactly("content");
    }

    @Test
    void renderCurrentPage_malformedConfigJson_fallsBackToEmptyMap_doesNotThrow() {
        PageFlow f = flow("f1", "s");
        FlowSession s = session("tok", "f1", "p1", false);
        when(flowSessionService.get("tok")).thenReturn(s);
        when(pageFlowRepo.findById("f1")).thenReturn(Optional.of(f));
        Page p = page("p1", "f1");
        when(pageRepo.findById("p1")).thenReturn(Optional.of(p));
        when(pageWidgetRepo.findByPageIdOrderByOrderInSlotAsc("p1"))
            .thenReturn(List.of(widget("p1", "main", 0, "content", "not json")));
        when(pageTransitionService.outcomesForPage(p)).thenReturn(List.of(WidgetOutcome.DEFAULT));

        PublicFlowRuntimeService.PublicSessionView view = service.resume("tok");

        assertThat(view.page().widgets().get(0).config()).isEmpty();
    }

    @Test
    void resume_endedSession_returnsEndedViewWithNullPage() {
        PageFlow f = flow("f1", "s");
        FlowSession s = session("tok", "f1", null, true);
        when(flowSessionService.get("tok")).thenReturn(s);
        when(pageFlowRepo.findById("f1")).thenReturn(Optional.of(f));

        PublicFlowRuntimeService.PublicSessionView view = service.resume("tok");

        assertThat(view.ended()).isTrue();
        assertThat(view.page()).isNull();
    }

    @Test
    void advance_movesToNextPage_returnsUpdatedView() {
        FlowSession updated = session("tok", "f1", "p2", false);
        when(flowSessionService.advance("tok", "next"))
            .thenReturn(new FlowSessionService.AdvanceResult(updated, null, null));
        PageFlow f = flow("f1", "s");
        when(pageFlowRepo.findById("f1")).thenReturn(Optional.of(f));
        Page p2 = page("p2", "f1");
        when(pageRepo.findById("p2")).thenReturn(Optional.of(p2));
        when(pageWidgetRepo.findByPageIdOrderByOrderInSlotAsc("p2")).thenReturn(List.of());
        when(pageTransitionService.outcomesForPage(p2)).thenReturn(List.of(WidgetOutcome.DEFAULT));

        PublicFlowRuntimeService.PublicSessionView view = service.advance("tok", "next");

        assertThat(view.page().pageId()).isEqualTo("p2");
    }

    @Test
    void advance_redirectFlow_startsNewSessionOnTargetFlow_returnsItsSlug() {
        FlowSession ended = session("tok", "f1", null, true);
        when(flowSessionService.advance("tok", "next"))
            .thenReturn(new FlowSessionService.AdvanceResult(ended, "f2", null));
        FlowSession newSession = session("tok2", "f2", "p9", false);
        when(flowSessionService.start("f2")).thenReturn(newSession);
        PageFlow f2 = flow("f2", "target-flow");
        when(pageFlowRepo.findById("f2")).thenReturn(Optional.of(f2));
        Page p9 = page("p9", "f2");
        when(pageRepo.findById("p9")).thenReturn(Optional.of(p9));
        when(pageWidgetRepo.findByPageIdOrderByOrderInSlotAsc("p9")).thenReturn(List.of());
        when(pageTransitionService.outcomesForPage(p9)).thenReturn(List.of(WidgetOutcome.DEFAULT));

        PublicFlowRuntimeService.PublicSessionView view = service.advance("tok", "next");

        assertThat(view.token()).isEqualTo("tok2");
        assertThat(view.slug()).isEqualTo("target-flow");
    }

    @Test
    void advance_createEngagementEndAction_returnsEngagementIdInView() {
        FlowSession ended = session("tok", "f1", null, true);
        when(flowSessionService.advance("tok", "next"))
            .thenReturn(new FlowSessionService.AdvanceResult(ended, null, "eng-1"));
        PageFlow f = flow("f1", "s");
        when(pageFlowRepo.findById("f1")).thenReturn(Optional.of(f));

        PublicFlowRuntimeService.PublicSessionView view = service.advance("tok", "next");

        assertThat(view.ended()).isTrue();
        assertThat(view.page()).isNull();
        assertThat(view.engagementId()).isEqualTo("eng-1");
    }

    @Test
    void advance_alreadyEndedSession_propagatesIllegalState() {
        when(flowSessionService.advance("tok", "next"))
            .thenThrow(new IllegalStateException("FlowSession has already ended"));

        assertThatThrownBy(() -> service.advance("tok", "next")).isInstanceOf(IllegalStateException.class);
    }
}
