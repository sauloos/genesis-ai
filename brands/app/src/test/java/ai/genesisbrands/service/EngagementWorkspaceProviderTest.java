package ai.genesisbrands.service;

import ai.genesisbrands.agent.copy.CopyOutput;
import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.agent.visualidentity.VisualIdentityOutput;
import ai.genesisbrands.model.Engagement;
import ai.genesisbrands.repository.EngagementDirectionVersionRepository;
import ai.genesisbrands.repository.EngagementRepository;
import ai.genesisbrands.service.EngagementOrchestratorService.DirectionOutput;
import ai.genesisbrands.service.EngagementOrchestratorService.EngagementResults;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** The chooser's server side: who may choose, and the words/palette the decision cards show. */
class EngagementWorkspaceProviderTest {

    private final EngagementRepository repo = mock(EngagementRepository.class);
    private final EngagementDirectionVersionRepository versions = mock(EngagementDirectionVersionRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private EngagementWorkspaceProvider provider;

    @BeforeEach
    void setUp() {
        provider = new EngagementWorkspaceProvider(repo, versions, mapper);
        ReflectionTestUtils.setField(provider, "anchoredDescriptor", "Builds on who you are today");
        ReflectionTestUtils.setField(provider, "evolvedDescriptor", "A confident step forward");
        ReflectionTestUtils.setField(provider, "disruptiveDescriptor", "The boldest move in your category");
        when(repo.save(any(Engagement.class))).thenAnswer(i -> i.getArgument(0));
    }

    private DirectionOutput direction(String name, DirectionBrief.PurposeDerivation purpose,
                                      VisualIdentityOutput visual, String tagline) {
        DirectionBrief brief = new DirectionBrief("e1",
            new DirectionBrief.BrandContext("One Way", null, null, null, null, List.of(), null),
            null, null, null, null, purpose);
        CopyOutput copy = tagline == null ? null : new CopyOutput("e1", tagline, null, null, null, null, null,
            List.of(), List.of(), null, 1);
        return new DirectionOutput(name, brief, copy, visual, null, null, null, name.toLowerCase() + ".pdf", null);
    }

    private Engagement engagement(Engagement.Status status, String owner, DirectionOutput... dirs) throws Exception {
        Engagement e = new Engagement();
        e.setId("e1");
        e.setStatus(status);
        e.setClientUserId(owner);
        e.setResultsJson(mapper.writeValueAsString(new EngagementResults(List.of(dirs))));
        when(repo.findById("e1")).thenReturn(Optional.of(e));
        return e;
    }

    @Test
    void chooseRecordsTheDirectionForTheOwnerOfAFinishedEngagement() throws Exception {
        Engagement e = engagement(Engagement.Status.DONE, "u1",
            direction("ANCHORED", null, null, null), direction("EVOLVED", null, null, null));

        ClientWorkItem item = provider.choose("e1", "evolved", "u1");

        assertThat(e.getChosenDirection()).isEqualTo("EVOLVED");
        verify(repo).save(e);
        assertThat(item.variants()).filteredOn(ClientWorkItem.Variant::chosen)
            .extracting(ClientWorkItem.Variant::key).containsExactly("EVOLVED");
    }

    @Test
    void anotherClientsEngagementLooksNonExistent() throws Exception {
        engagement(Engagement.Status.DONE, "u1", direction("ANCHORED", null, null, null));
        assertThatThrownBy(() -> provider.choose("e1", "ANCHORED", "intruder"))
            .isInstanceOf(NoSuchElementException.class);
        verify(repo, never()).save(any());
    }

    @Test
    void anUnknownDirectionIsRejected() throws Exception {
        engagement(Engagement.Status.DONE, "u1", direction("ANCHORED", null, null, null));
        assertThatThrownBy(() -> provider.choose("e1", "WILD", "u1"))
            .isInstanceOf(NoSuchElementException.class);
        verify(repo, never()).save(any());
    }

    @Test
    void anUnfinishedEngagementCannotBeChosenFrom() throws Exception {
        engagement(Engagement.Status.RUNNING, "u1", direction("ANCHORED", null, null, null));
        assertThatThrownBy(() -> provider.choose("e1", "ANCHORED", "u1"))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(repo, never()).save(any());
    }

    @Test
    void listMineFillsTheDecisionForTheCurrentVariant() throws Exception {
        var purpose = new DirectionBrief.PurposeDerivation("  A calmer voice ", "t", "Sage", "r", "v",
            List.of("Your name", " ", "Your colours", "Your roots", "A fourth"),
            List.of("The old logo"), "a", "People over process", "May feel quiet", List.of(), List.of());
        var visual = new VisualIdentityOutput("e1", List.of(
            new VisualIdentityOutput.ColorSwatch("Ink", "#112233", "primary", "x"),
            new VisualIdentityOutput.ColorSwatch("Bad", "red", "accent", "x"),
            new VisualIdentityOutput.ColorSwatch("Short", "#123", "accent", "x"),
            new VisualIdentityOutput.ColorSwatch("Sand", "#AABBCC", "neutral", "x")),
            new VisualIdentityOutput.Typography("Fraunces", "Inter", "p"), null, null, null, null, null, 1);
        Engagement e = engagement(Engagement.Status.DONE, "u1", direction("EVOLVED", purpose, visual, " Here to stay "));
        when(repo.findAllByClientUserIdOrderByCreatedAtDesc("u1")).thenReturn(List.of(e));

        ClientWorkItem.Decision d = provider.listMine("u1").get(0).variants().get(0).decision();

        assertThat(d).isNotNull();
        assertThat(d.label()).isEqualTo("Evolved");
        assertThat(d.descriptor()).isEqualTo("A confident step forward");
        assertThat(d.tagline()).isEqualTo("Here to stay");
        assertThat(d.headlineFont()).isEqualTo("Fraunces");
        assertThat(d.summary()).isEqualTo("A calmer voice");
        assertThat(d.personality()).isEqualTo("Sage");
        assertThat(d.palette()).containsExactly("#112233", "#AABBCC");
        assertThat(d.keeps()).containsExactly("Your name", "Your colours", "Your roots"); // blank dropped, max 3
        assertThat(d.letsGo()).containsExactly("The old logo");
        assertThat(d.bet()).isEqualTo("People over process");
        assertThat(d.risk()).isEqualTo("May feel quiet");
        assertThat(d.previewUrl()).isEqualTo("/api/engagements/e1/preview/EVOLVED");
    }

    @Test
    void aDirectionWithoutABriefStillGetsALabelAndDescriptor() throws Exception {
        Engagement e = engagement(Engagement.Status.DONE, "u1", new DirectionOutput("ANCHORED", null, null, null,
            null, null, null, "a.pdf", null));
        when(repo.findAllByClientUserIdOrderByCreatedAtDesc("u1")).thenReturn(List.of(e));

        ClientWorkItem.Decision d = provider.listMine("u1").get(0).variants().get(0).decision();

        assertThat(d.label()).isEqualTo("Anchored");
        assertThat(d.descriptor()).isEqualTo("Builds on who you are today");
        assertThat(d.tagline()).isNull();
        assertThat(d.palette()).isEmpty();
        assertThat(d.keeps()).isEmpty();
    }
}
