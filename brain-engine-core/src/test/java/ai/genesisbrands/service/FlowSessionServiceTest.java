package ai.genesisbrands.service;

import ai.genesisbrands.model.FlowSession;
import ai.genesisbrands.model.Page;
import ai.genesisbrands.model.PageFlow;
import ai.genesisbrands.model.PageTransition;
import ai.genesisbrands.repository.FlowSessionEventRepository;
import ai.genesisbrands.repository.FlowSessionRepository;
import ai.genesisbrands.repository.PageFlowRepository;
import ai.genesisbrands.repository.PageRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlowSessionServiceTest {

    @Mock private FlowSessionRepository sessionRepo;
    @Mock private FlowSessionEventRepository eventRepo;
    @Mock private PageFlowRepository pageFlowRepo;
    @Mock private PageRepository pageRepo;
    @Mock private PageTransitionService pageTransitionService;
    @Mock private FlowEngagementService flowEngagementService;

    private FlowSessionService service;

    @BeforeEach
    void setUp() {
        service = new FlowSessionService(sessionRepo, eventRepo, pageFlowRepo, pageRepo, pageTransitionService, flowEngagementService, new ObjectMapper());
    }

    private PageFlow flow(String id, String startPageId, String endAction, String endPageId, String endTargetFlowId) {
        PageFlow f = new PageFlow();
        f.setId(id);
        f.setStartPageId(startPageId);
        f.setEndAction(endAction);
        f.setEndPageId(endPageId);
        f.setEndTargetFlowId(endTargetFlowId);
        return f;
    }

    private Page page(String id, String flowId, Instant createdAt) {
        Page p = new Page();
        p.setId(id);
        p.setPageFlowId(flowId);
        p.setCreatedAt(createdAt);
        return p;
    }

    private FlowSession session(String token, String flowId, String currentPageId) {
        FlowSession s = new FlowSession();
        s.setToken(token);
        s.setPageFlowId(flowId);
        s.setCurrentPageId(currentPageId);
        s.setExpiresAt(Instant.now().plusSeconds(3600));
        return s;
    }

    private PageTransition transition(String targetKind, String targetPageId) {
        PageTransition t = new PageTransition();
        t.setTargetKind(targetKind);
        t.setTargetPageId(targetPageId);
        return t;
    }

    @Test
    void start_requiresAtLeastOnePageWhenNoStartPageConfigured() {
        when(pageFlowRepo.findById("f1")).thenReturn(Optional.of(flow("f1", null, null, null, null)));
        when(pageRepo.findByPageFlowId("f1")).thenReturn(List.of());

        assertThatThrownBy(() -> service.start("f1", false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void start_missingFlowThrows() {
        when(pageFlowRepo.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.start("missing", false)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void start_createsSessionAtFlowsStartPage() {
        when(pageFlowRepo.findById("f1")).thenReturn(Optional.of(flow("f1", "p1", null, null, null)));
        when(sessionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FlowSession s = service.start("f1", false);

        assertThat(s.getPageFlowId()).isEqualTo("f1");
        assertThat(s.getCurrentPageId()).isEqualTo("p1");
        assertThat(s.isEnded()).isFalse();
        assertThat(s.isSimulated()).isFalse();
        assertThat(s.getExpiresAt()).isAfter(Instant.now().plusSeconds(3600 * 23));
    }

    @Test
    void start_marksSessionSimulatedWhenRequested() {
        when(pageFlowRepo.findById("f1")).thenReturn(Optional.of(flow("f1", "p1", null, null, null)));
        when(sessionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FlowSession s = service.start("f1", true);

        assertThat(s.isSimulated()).isTrue();
    }

    @Test
    void start_fallsBackToEarliestCreatedPage_whenNoExplicitStartPageId() {
        when(pageFlowRepo.findById("f1")).thenReturn(Optional.of(flow("f1", null, null, null, null)));
        when(pageRepo.findByPageFlowId("f1")).thenReturn(List.of(
            page("newer", "f1", Instant.parse("2026-01-02T00:00:00Z")),
            page("older", "f1", Instant.parse("2026-01-01T00:00:00Z"))
        ));
        when(sessionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FlowSession s = service.start("f1", false);

        assertThat(s.getCurrentPageId()).isEqualTo("older");
    }

    @Test
    void get_expiredOrMissingThrows() {
        when(sessionRepo.findByTokenAndExpiresAtAfter(anyString(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("tok")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void advance_movesToTargetPageOnPageTransition() {
        FlowSession s = session("tok", "f1", "p1");
        when(sessionRepo.findByTokenAndExpiresAtAfter(anyString(), any())).thenReturn(Optional.of(s));
        when(pageTransitionService.resolve("p1", "next")).thenReturn(Optional.of(transition("PAGE", "p2")));
        when(sessionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FlowSessionService.AdvanceResult result = service.advance("tok", "next");

        assertThat(result.session().getCurrentPageId()).isEqualTo("p2");
        assertThat(result.session().isEnded()).isFalse();
        assertThat(result.redirectToFlowId()).isNull();
    }

    @Test
    void advance_flowEndWithNoEndActionJustEnds() {
        FlowSession s = session("tok", "f1", "p1");
        when(sessionRepo.findByTokenAndExpiresAtAfter(anyString(), any())).thenReturn(Optional.of(s));
        when(pageTransitionService.resolve("p1", "next")).thenReturn(Optional.of(transition("FLOW_END", null)));
        when(pageFlowRepo.findById("f1")).thenReturn(Optional.of(flow("f1", "p1", null, null, null)));
        when(sessionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FlowSessionService.AdvanceResult result = service.advance("tok", "next");

        assertThat(result.session().isEnded()).isTrue();
        assertThat(result.session().getCurrentPageId()).isNull();
        assertThat(result.redirectToFlowId()).isNull();
    }

    @Test
    void advance_flowEndWithEndPageShowsIt() {
        FlowSession s = session("tok", "f1", "p1");
        when(sessionRepo.findByTokenAndExpiresAtAfter(anyString(), any())).thenReturn(Optional.of(s));
        when(pageTransitionService.resolve("p1", "next")).thenReturn(Optional.of(transition("FLOW_END", null)));
        when(pageFlowRepo.findById("f1")).thenReturn(Optional.of(flow("f1", "p1", "END_PAGE", "pEnd", null)));
        when(sessionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FlowSessionService.AdvanceResult result = service.advance("tok", "next");

        assertThat(result.session().isEnded()).isTrue();
        assertThat(result.session().getCurrentPageId()).isEqualTo("pEnd");
        assertThat(result.redirectToFlowId()).isNull();
    }

    @Test
    void advance_flowEndWithRedirectFlowReturnsTargetFlowId() {
        FlowSession s = session("tok", "f1", "p1");
        when(sessionRepo.findByTokenAndExpiresAtAfter(anyString(), any())).thenReturn(Optional.of(s));
        when(pageTransitionService.resolve("p1", "next")).thenReturn(Optional.of(transition("FLOW_END", null)));
        when(pageFlowRepo.findById("f1")).thenReturn(Optional.of(flow("f1", "p1", "REDIRECT_FLOW", null, "f2")));
        when(sessionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FlowSessionService.AdvanceResult result = service.advance("tok", "next");

        assertThat(result.session().isEnded()).isTrue();
        assertThat(result.session().getCurrentPageId()).isNull();
        assertThat(result.redirectToFlowId()).isEqualTo("f2");
    }

    @Test
    void advance_flowEndWithCreateEngagementTriggersFlowEngagementService() {
        FlowSession s = session("tok", "f1", "p1");
        when(sessionRepo.findByTokenAndExpiresAtAfter(anyString(), any())).thenReturn(Optional.of(s));
        when(pageTransitionService.resolve("p1", "next")).thenReturn(Optional.of(transition("FLOW_END", null)));
        PageFlow f = flow("f1", "p1", "CREATE_ENGAGEMENT", null, null);
        when(pageFlowRepo.findById("f1")).thenReturn(Optional.of(f));
        when(sessionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        when(flowEngagementService.triggerEngagement(f, s)).thenReturn("eng-1");

        FlowSessionService.AdvanceResult result = service.advance("tok", "next");

        assertThat(result.session().isEnded()).isTrue();
        assertThat(result.session().getCurrentPageId()).isNull();
        assertThat(result.redirectToFlowId()).isNull();
        assertThat(result.engagementId()).isEqualTo("eng-1");
        org.mockito.Mockito.verify(flowEngagementService).triggerEngagement(f, s);
    }

    @Test
    void advance_simulatedSessionSkipsCreateEngagementSideEffect() {
        FlowSession s = session("tok", "f1", "p1");
        s.setSimulated(true);
        when(sessionRepo.findByTokenAndExpiresAtAfter(anyString(), any())).thenReturn(Optional.of(s));
        when(pageTransitionService.resolve("p1", "next")).thenReturn(Optional.of(transition("FLOW_END", null)));
        PageFlow f = flow("f1", "p1", "CREATE_ENGAGEMENT", null, null);
        when(pageFlowRepo.findById("f1")).thenReturn(Optional.of(f));
        when(sessionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FlowSessionService.AdvanceResult result = service.advance("tok", "next");

        assertThat(result.session().isEnded()).isTrue();
        assertThat(result.engagementId()).isNull();
        org.mockito.Mockito.verify(flowEngagementService, org.mockito.Mockito.never()).triggerEngagement(any(), any());
    }

    @Test
    void advance_alreadyEndedSessionThrows() {
        FlowSession s = session("tok", "f1", null);
        s.setEnded(true);
        when(sessionRepo.findByTokenAndExpiresAtAfter(anyString(), any())).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> service.advance("tok", "next")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void advance_unresolvableOutcomeThrows() {
        FlowSession s = session("tok", "f1", "p1");
        when(sessionRepo.findByTokenAndExpiresAtAfter(anyString(), any())).thenReturn(Optional.of(s));
        when(pageTransitionService.resolve("p1", "ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.advance("tok", "ghost")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateContext_mergesPatchIntoExistingContext() {
        FlowSession s = session("tok", "f1", "p1");
        s.setContextJson("{\"a\":1,\"b\":2}");
        when(sessionRepo.findByTokenAndExpiresAtAfter(anyString(), any())).thenReturn(Optional.of(s));
        when(sessionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FlowSession updated = service.updateContext("tok", "{\"b\":3,\"c\":4}");

        assertThat(updated.getContextJson()).contains("\"a\":1").contains("\"b\":3").contains("\"c\":4");
    }

    @Test
    void updateContext_invalidJsonThrows() {
        FlowSession s = session("tok", "f1", "p1");
        when(sessionRepo.findByTokenAndExpiresAtAfter(anyString(), any())).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> service.updateContext("tok", "not json")).isInstanceOf(IllegalArgumentException.class);
    }
}
