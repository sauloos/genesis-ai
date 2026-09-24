package ai.genesisbrands.controller;

import ai.genesisbrands.agent.brandbook.BrandBookTemplateRenderer;
import ai.genesisbrands.model.ClientUser;
import ai.genesisbrands.model.Engagement;
import ai.genesisbrands.repository.EngagementRepository;
import ai.genesisbrands.security.AdminAuthHelper;
import ai.genesisbrands.security.ClientAuthHelper;
import ai.genesisbrands.service.BlobStorageService;
import ai.genesisbrands.service.EngagementOrchestratorService;
import ai.genesisbrands.service.EngagementOrchestratorService.DirectionOutput;
import ai.genesisbrands.service.EngagementOrchestratorService.EngagementResults;
import ai.genesisbrands.service.PhotoSourcingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EngagementControllerTest {

    @Mock private EngagementRepository engagementRepo;
    @Mock private EngagementOrchestratorService orchestrator;
    @Mock private BrandBookTemplateRenderer pdfRenderer;
    @Mock private BlobStorageService blobStorageService;
    @Mock private PhotoSourcingService photoSourcingService;
    @Mock private AdminAuthHelper adminAuth;
    @Mock private ClientAuthHelper clientAuthHelper;
    @Mock private HttpServletRequest req;

    private EngagementController controller;

    @BeforeEach
    void setUp() {
        controller = new EngagementController(engagementRepo, orchestrator, pdfRenderer, blobStorageService,
            photoSourcingService, new ObjectMapper(), adminAuth, clientAuthHelper);
    }

    private ClientUser clientUser(String id) {
        ClientUser u = new ClientUser();
        u.setId(id);
        return u;
    }

    private Engagement engagementWithResults(String id, String ownerId, Engagement.Status status, String direction) {
        Engagement e = new Engagement();
        e.setId(id);
        e.setSource(Engagement.Source.CLIENT);
        e.setClientUserId(ownerId);
        e.setStatus(status);
        EngagementResults results = new EngagementResults(List.of(
            new DirectionOutput(direction, null, null, null, null, null, null, null, null)
        ));
        try {
            e.setResultsJson(new ObjectMapper().writeValueAsString(results));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return e;
    }

    // ── choose-direction ─────────────────────────────────────────────────────

    @Test
    void chooseDirection_anonymousCaller_throwsForbidden() {
        Engagement e = engagementWithResults("e1", "owner-1", Engagement.Status.DONE, "ANCHORED");
        when(engagementRepo.findById("e1")).thenReturn(Optional.of(e));
        when(clientAuthHelper.resolve(req)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            controller.chooseDirection("e1", new EngagementController.ChooseDirectionRequest("ANCHORED"), req))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void chooseDirection_callerIsNotOwner_throwsForbidden() {
        Engagement e = engagementWithResults("e1", "owner-1", Engagement.Status.DONE, "ANCHORED");
        when(engagementRepo.findById("e1")).thenReturn(Optional.of(e));
        when(clientAuthHelper.resolve(req)).thenReturn(Optional.of(clientUser("intruder")));

        assertThatThrownBy(() ->
            controller.chooseDirection("e1", new EngagementController.ChooseDirectionRequest("ANCHORED"), req))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void chooseDirection_ownerButEngagementNotDone_throwsConflict() {
        Engagement e = engagementWithResults("e1", "owner-1", Engagement.Status.RUNNING, "ANCHORED");
        when(engagementRepo.findById("e1")).thenReturn(Optional.of(e));
        when(clientAuthHelper.resolve(req)).thenReturn(Optional.of(clientUser("owner-1")));

        assertThatThrownBy(() ->
            controller.chooseDirection("e1", new EngagementController.ChooseDirectionRequest("ANCHORED"), req))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void chooseDirection_unknownDirection_throwsBadRequest() {
        Engagement e = engagementWithResults("e1", "owner-1", Engagement.Status.DONE, "ANCHORED");
        when(engagementRepo.findById("e1")).thenReturn(Optional.of(e));
        when(clientAuthHelper.resolve(req)).thenReturn(Optional.of(clientUser("owner-1")));

        assertThatThrownBy(() ->
            controller.chooseDirection("e1", new EngagementController.ChooseDirectionRequest("GHOST"), req))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void chooseDirection_ownerWithValidDirection_persistsChoice() {
        Engagement e = engagementWithResults("e1", "owner-1", Engagement.Status.DONE, "ANCHORED");
        when(engagementRepo.findById("e1")).thenReturn(Optional.of(e));
        when(clientAuthHelper.resolve(req)).thenReturn(Optional.of(clientUser("owner-1")));
        when(engagementRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EngagementController.EngagementSummary summary =
            controller.chooseDirection("e1", new EngagementController.ChooseDirectionRequest("anchored"), req);

        assertThat(summary.id()).isEqualTo("e1");
        assertThat(e.getChosenDirection()).isEqualTo("ANCHORED");
    }

    // ── preview ───────────────────────────────────────────────────────────────

    @Test
    void preview_callerIsNotOwner_throwsForbidden() {
        Engagement e = engagementWithResults("e1", "owner-1", Engagement.Status.DONE, "ANCHORED");
        when(engagementRepo.findById("e1")).thenReturn(Optional.of(e));
        when(clientAuthHelper.resolve(req)).thenReturn(Optional.of(clientUser("intruder")));

        assertThatThrownBy(() -> controller.preview("e1", "anchored", req))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void preview_ownerWithUnknownDirection_returnsNotFound() {
        Engagement e = engagementWithResults("e1", "owner-1", Engagement.Status.DONE, "ANCHORED");
        when(engagementRepo.findById("e1")).thenReturn(Optional.of(e));
        when(clientAuthHelper.resolve(req)).thenReturn(Optional.of(clientUser("owner-1")));

        ResponseEntity<byte[]> resp = controller.preview("e1", "ghost", req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void preview_ownerWithValidDirection_returnsWatermarkedJpeg() {
        Engagement e = engagementWithResults("e1", "owner-1", Engagement.Status.DONE, "ANCHORED");
        when(engagementRepo.findById("e1")).thenReturn(Optional.of(e));
        when(clientAuthHelper.resolve(req)).thenReturn(Optional.of(clientUser("owner-1")));
        byte[] jpeg = new byte[] {1, 2, 3};
        when(pdfRenderer.renderPreviewImage(any(), any())).thenReturn(jpeg);

        ResponseEntity<byte[]> resp = controller.preview("e1", "anchored", req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEqualTo(jpeg);
    }
}
