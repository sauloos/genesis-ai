package ai.genesisbrands.controller;

import ai.genesisbrands.model.Engagement;
import ai.genesisbrands.model.FlowSession;
import ai.genesisbrands.model.PageFlow;
import ai.genesisbrands.repository.EngagementRepository;
import ai.genesisbrands.repository.PageFlowRepository;
import ai.genesisbrands.service.FlowEngagementService;
import ai.genesisbrands.service.FlowSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrandResultsControllerTest {

    @Mock private FlowSessionService flowSessionService;
    @Mock private PageFlowRepository pageFlowRepo;
    @Mock private FlowEngagementService flowEngagementService;
    @Mock private EngagementRepository engagementRepo;

    private BrandResultsController controller;

    @BeforeEach
    void setUp() {
        controller = new BrandResultsController(flowSessionService, pageFlowRepo, flowEngagementService,
            engagementRepo, new ObjectMapper());
    }

    private FlowSession session(String token, String pageFlowId, String clientUserId, String contextJson) {
        FlowSession s = new FlowSession();
        s.setToken(token);
        s.setPageFlowId(pageFlowId);
        s.setClientUserId(clientUserId);
        s.setContextJson(contextJson);
        s.setExpiresAt(Instant.now().plusSeconds(3600));
        return s;
    }

    private PageFlow flow(String id) {
        PageFlow f = new PageFlow();
        f.setId(id);
        return f;
    }

    private Engagement engagement(String id, Engagement.Status status) {
        Engagement e = new Engagement();
        e.setId(id);
        e.setStatus(status);
        return e;
    }

    @Test
    void start_anonymousSession_throwsForbidden() {
        when(flowSessionService.getForUpdate("tok")).thenReturn(session("tok", "f1", null, null));

        assertThatThrownBy(() -> controller.start("tok", "w1", false))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("403");
    }

    @Test
    void start_missingSession_throwsNotFound() {
        when(flowSessionService.getForUpdate("tok")).thenThrow(new NoSuchElementException("gone"));

        assertThatThrownBy(() -> controller.start("tok", "w1", false))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("404");
    }

    @Test
    void start_firstCall_triggersPipelineAndPersistsEngagementIdOnSession() {
        FlowSession s = session("tok", "f1", "cu-1", null);
        when(flowSessionService.getForUpdate("tok")).thenReturn(s);
        PageFlow f = flow("f1");
        when(pageFlowRepo.findById("f1")).thenReturn(Optional.of(f));
        when(flowEngagementService.triggerEngagement(f, s)).thenReturn("eng-1");

        BrandResultsController.StartResponse resp = controller.start("tok", "w1", false);

        assertThat(resp.engagementId()).isEqualTo("eng-1");
        assertThat(resp.status()).isEqualTo("PENDING");
        verify(flowSessionService).updateContext(eq("tok"), org.mockito.ArgumentMatchers.contains("eng-1"));
    }

    @Test
    void start_secondCall_alreadyStarted_returnsSameEngagementId_doesNotTriggerAgain() {
        FlowSession s = session("tok", "f1", "cu-1", "{\"__engagement:w1\":\"eng-1\"}");
        when(flowSessionService.getForUpdate("tok")).thenReturn(s);
        when(engagementRepo.findById("eng-1")).thenReturn(Optional.of(engagement("eng-1", Engagement.Status.RUNNING)));

        BrandResultsController.StartResponse resp = controller.start("tok", "w1", false);

        assertThat(resp.engagementId()).isEqualTo("eng-1");
        assertThat(resp.status()).isEqualTo("RUNNING");
        verify(flowEngagementService, never()).triggerEngagement(any(), any());
    }

    @Test
    void start_retryRequested_butEngagementStillRunning_isIgnored_returnsSameEngagement() {
        FlowSession s = session("tok", "f1", "cu-1", "{\"__engagement:w1\":\"eng-1\"}");
        when(flowSessionService.getForUpdate("tok")).thenReturn(s);
        when(engagementRepo.findById("eng-1")).thenReturn(Optional.of(engagement("eng-1", Engagement.Status.RUNNING)));

        BrandResultsController.StartResponse resp = controller.start("tok", "w1", true);

        assertThat(resp.engagementId()).isEqualTo("eng-1");
        verify(flowEngagementService, never()).triggerEngagement(any(), any());
    }

    @Test
    void start_retryRequested_engagementFailed_triggersNewPipelineRun() {
        FlowSession s = session("tok", "f1", "cu-1", "{\"__engagement:w1\":\"eng-1\"}");
        when(flowSessionService.getForUpdate("tok")).thenReturn(s);
        when(engagementRepo.findById("eng-1")).thenReturn(Optional.of(engagement("eng-1", Engagement.Status.FAILED)));
        PageFlow f = flow("f1");
        when(pageFlowRepo.findById("f1")).thenReturn(Optional.of(f));
        when(flowEngagementService.triggerEngagement(f, s)).thenReturn("eng-2");

        BrandResultsController.StartResponse resp = controller.start("tok", "w1", true);

        assertThat(resp.engagementId()).isEqualTo("eng-2");
        verify(flowEngagementService).triggerEngagement(f, s);
    }
}
