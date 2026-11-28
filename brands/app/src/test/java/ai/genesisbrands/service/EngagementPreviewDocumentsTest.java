package ai.genesisbrands.service;

import ai.genesisbrands.agent.brandbook.BrandBookTemplateRenderer;
import ai.genesisbrands.model.Engagement;
import ai.genesisbrands.repository.EngagementRepository;
import ai.genesisbrands.service.DocumentPreviewProvider.Access;
import ai.genesisbrands.service.DocumentPreviewProvider.PageSpec;
import ai.genesisbrands.service.EngagementOrchestratorService.DirectionOutput;
import ai.genesisbrands.service.EngagementOrchestratorService.EngagementResults;
import ai.genesisbrands.agent.brandbook.BrandBookOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EngagementPreviewDocumentsTest {

    private final EngagementRepository repo = mock(EngagementRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private EngagementPreviewDocuments docs;

    @BeforeEach
    void setUp() {
        docs = new EngagementPreviewDocuments(repo, mapper, mock(BrandBookTemplateRenderer.class));
    }

    private Engagement engagement(Engagement.Status status, String owner, String... directions) throws Exception {
        Engagement e = new Engagement();
        e.setId("e1");
        e.setStatus(status);
        e.setClientUserId(owner);
        List<DirectionOutput> outs = new java.util.ArrayList<>();
        for (String d : directions) {
            outs.add(new DirectionOutput(d, null, null, null, null, null, brandBook(), d + ".pdf", null));
        }
        e.setResultsJson(mapper.writeValueAsString(new EngagementResults(outs)));
        when(repo.findById("e1")).thenReturn(Optional.of(e));
        when(repo.findAllByClientUserIdOrderByCreatedAtDesc(owner)).thenReturn(List.of(e));
        return e;
    }

    private static BrandBookOutput brandBook() {
        return new BrandBookOutput("e1", null, null, null, null, null, null, null, null, null, null, null, 1);
    }

    @Test
    void onlyTheOwnerOfAFinishedEngagementMayPreview() throws Exception {
        engagement(Engagement.Status.DONE, "u1", "EVOLVED");
        assertThat(docs.find("e1", "evolved", "u1")).isPresent();
        assertThat(docs.find("e1", "EVOLVED", "intruder")).isEmpty();
        assertThat(docs.find("e1", "WILD", "u1")).isEmpty();
        assertThat(docs.find("nope", "EVOLVED", "u1")).isEmpty();
    }

    @Test
    void anUnfinishedEngagementCannotBePreviewed() throws Exception {
        engagement(Engagement.Status.RUNNING, "u1", "EVOLVED");
        assertThat(docs.find("e1", "EVOLVED", "u1")).isEmpty();
        assertThat(docs.listPreviewable("u1")).isEmpty();
    }

    @Test
    void listsEachDirectionAsADocumentWithAVersionThatTracksContent() throws Exception {
        engagement(Engagement.Status.DONE, "u1", "ANCHORED", "EVOLVED");
        var list = docs.listPreviewable("u1");
        assertThat(list).extracting(DocumentPreviewProvider.Document::variantKey).containsExactly("ANCHORED", "EVOLVED");
        assertThat(list.get(0).variantLabel()).isEqualTo("Anchored");
        assertThat(list.get(0).contentVersion()).endsWith("-" + EngagementPreviewDocuments.RENDER_VERSION)
            .isNotEqualTo(list.get(1).contentVersion());
        assertThat(docs.find("e1", "ANCHORED", "u1").get().contentVersion()).isEqualTo(list.get(0).contentVersion());
    }

    @Test
    void theDefaultCurationShowsTheOpeningLogoTasteAndClosingAndDescribesTheRest() {
        List<String> chapters = List.of("Cover", "Brand Statement", "Brand Statement", "Mission", "Vision",
            "Logo System", "Logo System", "Logo System", "Brand Colours", "Photography", "Photography",
            "Typography", "Applications", "Applications", "Closing", "Closing");

        List<PageSpec> specs = EngagementPreviewDocuments.curateChapters(chapters);

        assertThat(specs).hasSize(chapters.size());
        List<Integer> shown = specs.stream().filter(s -> s.access() == Access.SHOW).map(PageSpec::index).toList();
        assertThat(shown).containsExactly(0, 1, 2, 5, 6, 9, 12, 15);
        PageSpec colours = specs.get(8);
        assertThat(colours.access()).isEqualTo(Access.WITHHOLD);
        assertThat(colours.description()).contains("HEX").contains("Not shown in the preview");
        assertThat(colours.label()).isEqualTo("Page 9 · Brand Colours");
    }

    @Test
    void aShortBookStillShowsItsLastPageAndNeverFailsOnEmpty() {
        assertThat(EngagementPreviewDocuments.curateChapters(List.of())).isEmpty();
        var one = EngagementPreviewDocuments.curateChapters(List.of("Cover"));
        assertThat(one).hasSize(1);
        assertThat(one.get(0).access()).isEqualTo(Access.SHOW);
    }
}
