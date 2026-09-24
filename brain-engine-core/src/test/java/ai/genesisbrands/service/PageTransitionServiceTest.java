package ai.genesisbrands.service;

import ai.genesisbrands.model.Page;
import ai.genesisbrands.model.PageTransition;
import ai.genesisbrands.model.PageWidget;
import ai.genesisbrands.platform.WidgetDescriptor;
import ai.genesisbrands.platform.WidgetOutcome;
import ai.genesisbrands.repository.PageRepository;
import ai.genesisbrands.repository.PageTransitionRepository;
import ai.genesisbrands.repository.PageWidgetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PageTransitionServiceTest {

    @Mock private PageTransitionRepository transitionRepo;
    @Mock private PageRepository pageRepo;
    @Mock private PageWidgetRepository pageWidgetRepo;

    private PageTransitionService service;

    private Page page(String id, String flowId) {
        Page p = new Page();
        p.setId(id);
        p.setPageFlowId(flowId);
        p.setCreatedAt(Instant.now());
        return p;
    }

    private PageWidget widget(String pageId, String slotKey, int order, String widgetType) {
        PageWidget w = new PageWidget();
        w.setId("w-" + pageId + "-" + slotKey + "-" + order);
        w.setPageId(pageId);
        w.setSlotKey(slotKey);
        w.setOrderInSlot(order);
        w.setWidgetType(widgetType);
        return w;
    }

    private WidgetDescriptor descriptor(String widgetType, List<WidgetOutcome> outcomes) {
        return new WidgetDescriptor() {
            public String widgetType() { return widgetType; }
            public String displayName() { return widgetType; }
            public String description() { return ""; }
            public List<WidgetOutcome> outcomes() { return outcomes; }
        };
    }

    @BeforeEach
    void setUp() {
        List<WidgetDescriptor> descriptors = List.of(
            descriptor("content", List.of(WidgetOutcome.DEFAULT)),
            descriptor("payment", List.of(
                new WidgetOutcome("onSuccess", "On Success"),
                new WidgetOutcome("onFailure", "On Failure")))
        );
        service = new PageTransitionService(transitionRepo, pageRepo, pageWidgetRepo, descriptors);
    }

    @Test
    void outcomesForPage_noWidgets_fallsBackToDefaultPlusError() {
        Page p = page("p1", "f1");
        when(pageWidgetRepo.findByPageIdOrderByOrderInSlotAsc("p1")).thenReturn(List.of());

        List<WidgetOutcome> outcomes = service.outcomesForPage(p);

        assertThat(outcomes).extracting(WidgetOutcome::key).containsExactly("next", "error");
    }

    @Test
    void outcomesForPage_dedupesByKeyAndOrdersBySlotThenOrder_errorAlwaysLast() {
        Page p = page("p1", "f1");
        when(pageWidgetRepo.findByPageIdOrderByOrderInSlotAsc("p1")).thenReturn(List.of(
            widget("p1", "main", 1, "payment"),
            widget("p1", "main", 0, "content")
        ));

        List<WidgetOutcome> outcomes = service.outcomesForPage(p);

        // "content" (order 0) sorts before "payment" (order 1) within the same slot
        assertThat(outcomes).extracting(WidgetOutcome::key).containsExactly("next", "onSuccess", "onFailure", "error");
    }

    @Test
    void setTransition_rejectsOutcomeNotDeclaredByPagesWidgets() {
        Page source = page("p1", "f1");
        when(pageRepo.findById("p1")).thenReturn(Optional.of(source));
        when(pageWidgetRepo.findByPageIdOrderByOrderInSlotAsc("p1")).thenReturn(List.of());

        assertThatThrownBy(() -> service.setTransition("p1", "onSuccess", "PAGE", "p2"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("onSuccess");
    }

    @Test
    void setTransition_flowEndRejectsNonNullTargetPageId() {
        Page source = page("p1", "f1");
        when(pageRepo.findById("p1")).thenReturn(Optional.of(source));

        assertThatThrownBy(() -> service.setTransition("p1", "error", "FLOW_END", "p2"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setTransition_pageTargetMustShareFlow() {
        Page source = page("p1", "f1");
        Page target = page("p2", "OTHER_FLOW");
        when(pageRepo.findById("p1")).thenReturn(Optional.of(source));
        when(pageRepo.findById("p2")).thenReturn(Optional.of(target));
        when(pageWidgetRepo.findByPageIdOrderByOrderInSlotAsc("p1")).thenReturn(List.of());

        assertThatThrownBy(() -> service.setTransition("p1", "next", "PAGE", "p2"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("same PageFlow");
    }

    @Test
    void setTransition_upsertsAndPersists() {
        Page source = page("p1", "f1");
        Page target = page("p2", "f1");
        when(pageRepo.findById("p1")).thenReturn(Optional.of(source));
        when(pageRepo.findById("p2")).thenReturn(Optional.of(target));
        when(pageWidgetRepo.findByPageIdOrderByOrderInSlotAsc("p1")).thenReturn(List.of());
        when(transitionRepo.findBySourcePageIdAndOutcomeKey("p1", "next")).thenReturn(Optional.empty());
        when(transitionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PageTransition result = service.setTransition("p1", "next", "PAGE", "p2");

        assertThat(result.getSourcePageId()).isEqualTo("p1");
        assertThat(result.getOutcomeKey()).isEqualTo("next");
        assertThat(result.getTargetKind()).isEqualTo("PAGE");
        assertThat(result.getTargetPageId()).isEqualTo("p2");
    }

    @Test
    void resolve_returnsExplicitRowWhenPresent() {
        PageTransition explicit = new PageTransition();
        explicit.setTargetKind("PAGE");
        explicit.setTargetPageId("p9");
        when(transitionRepo.findBySourcePageIdAndOutcomeKey("p1", "error")).thenReturn(Optional.of(explicit));

        Optional<PageTransition> resolved = service.resolve("p1", "error");

        assertThat(resolved).contains(explicit);
    }

    @Test
    void resolve_errorFallsBackToFlowsDesignatedErrorPageExcludingSelf() {
        when(transitionRepo.findBySourcePageIdAndOutcomeKey("p1", "error")).thenReturn(Optional.empty());
        Page self = page("p1", "f1");
        self.setErrorPage(true); // even if self is flagged, it must be excluded from the fallback
        Page errorPage = page("p2", "f1");
        errorPage.setErrorPage(true);
        when(pageRepo.findById("p1")).thenReturn(Optional.of(self));
        when(pageRepo.findByPageFlowId("f1")).thenReturn(List.of(self, errorPage));

        Optional<PageTransition> resolved = service.resolve("p1", "error");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getTargetPageId()).isEqualTo("p2");
        assertThat(resolved.get().getTargetKind()).isEqualTo("PAGE");
    }

    @Test
    void resolve_nonErrorOutcomeWithNoExplicitRowIsEmpty() {
        when(transitionRepo.findBySourcePageIdAndOutcomeKey("p1", "next")).thenReturn(Optional.empty());

        assertThat(service.resolve("p1", "next")).isEmpty();
    }
}
