package ai.genesisbrands.controller;

import ai.genesisbrands.model.Engagement;
import ai.genesisbrands.repository.EngagementRepository;
import ai.genesisbrands.security.AdminAuthHelper;
import ai.genesisbrands.security.AdminSessionService;
import ai.genesisbrands.service.EngagementOrchestratorService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminEngagementControllerTest {

    @Mock private EngagementRepository engagementRepo;
    @Mock private EngagementOrchestratorService orchestrator;
    @Mock private AdminAuthHelper adminAuth;
    @Mock private AdminSessionService adminSession;
    @Mock private HttpServletRequest req;

    private AdminEngagementController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminEngagementController(engagementRepo, orchestrator, adminAuth, adminSession);
    }

    private Engagement done() {
        Engagement e = new Engagement();
        e.setId("e1");
        e.setSource(Engagement.Source.CLIENT);
        e.setClientUserId("client-1");
        e.setStatus(Engagement.Status.DONE);
        e.setQuestionnaireResponseId("old-response");
        e.setPurposePlaybookJson("{}");
        e.setBriefsJson("[]");
        e.setResultsJson("{\"directions\":[]}");
        e.setChosenDirection("ANCHORED");
        return e;
    }

    @Test
    void refusesWithoutAdminCredentials() {
        when(adminAuth.isAdminRequest(req)).thenReturn(false);
        when(adminSession.hasValidSession(req)).thenReturn(false);

        assertThatThrownBy(() -> controller.rerun("e1", null, req))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(orchestrator, never()).runEngagement(any());
    }

    @Test
    void refusesWhileTheEngagementIsStillRunning() {
        Engagement e = done();
        e.setStatus(Engagement.Status.RUNNING);
        when(adminAuth.isAdminRequest(req)).thenReturn(true);
        when(engagementRepo.findById("e1")).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> controller.rerun("e1", null, req))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);
        verify(orchestrator, never()).runEngagement(any());
    }

    @Test
    void clearsWhatTheLastRunDerivedKeepsTheOwnerAndStartsAgain() {
        Engagement e = done();
        when(adminAuth.isAdminRequest(req)).thenReturn(true);
        when(engagementRepo.findById("e1")).thenReturn(Optional.of(e));

        controller.rerun("e1", new AdminEngagementController.RerunRequest("new-response"), req);

        assertThat(e.getStatus()).isEqualTo(Engagement.Status.PENDING);
        assertThat(e.getQuestionnaireResponseId()).isEqualTo("new-response");
        assertThat(e.getClientUserId()).isEqualTo("client-1");
        assertThat(e.getPurposePlaybookJson()).isNull();
        assertThat(e.getBriefsJson()).isNull();
        assertThat(e.getResultsJson()).isNull();
        assertThat(e.getChosenDirection()).isNull();
        verify(engagementRepo).save(e);
        verify(orchestrator).runEngagement("e1");
    }

    @Test
    void keepsTheCurrentResponseWhenNoneIsGiven() {
        Engagement e = done();
        when(adminAuth.isAdminRequest(req)).thenReturn(true);
        when(engagementRepo.findById("e1")).thenReturn(Optional.of(e));

        controller.rerun("e1", null, req);

        assertThat(e.getQuestionnaireResponseId()).isEqualTo("old-response");
        verify(orchestrator).runEngagement("e1");
    }
}
