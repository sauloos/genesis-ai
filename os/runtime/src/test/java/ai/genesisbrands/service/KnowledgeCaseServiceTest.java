package ai.genesisbrands.service;

import ai.genesisbrands.model.KnowledgeCase;
import ai.genesisbrands.platform.knowledge.KnowledgeCases;
import ai.genesisbrands.repository.KnowledgeCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeCaseServiceTest {

    private final KnowledgeCaseRepository repository = mock(KnowledgeCaseRepository.class);
    private final KnowledgeCaseService service = new KnowledgeCaseService(repository, mock(BlobStorageService.class),
        mock(Layer1Service.class), new ObjectMapper(), mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS));

    @Test
    void sniffsImageTypesFromTheirFirstBytes() {
        assertThat(KnowledgeCaseService.sniff(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a})).isEqualTo("image/png");
        assertThat(KnowledgeCaseService.sniff(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0})).isEqualTo("image/jpeg");
        assertThat(KnowledgeCaseService.sniff("GIF89a".getBytes())).isEqualTo("image/gif");
        assertThat(KnowledgeCaseService.sniff("RIFF\0\0\0\0WEBPVP8 ".getBytes())).isEqualTo("image/webp");
        assertThat(KnowledgeCaseService.sniff("<svg onload=alert(1)>".getBytes())).isNull();
    }

    @Test
    void refusesAnUploadThatIsNotAnImage() {
        assertThatThrownBy(() -> service.create("logo", "BAD", "note", null, null, "<html>".getBytes(), "image/png"))
            .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void normalisesVerdictKindAndTags() {
        assertThat(KnowledgeCaseService.normaliseVerdict(" good ")).isEqualTo("GOOD");
        assertThatThrownBy(() -> KnowledgeCaseService.normaliseVerdict("meh")).isInstanceOf(IllegalArgumentException.class);
        assertThat(KnowledgeCaseService.normaliseKind("Visual Identity")).isEqualTo("visual-identity");
        assertThat(KnowledgeCaseService.normaliseTags("Coffee, negative space ,coffee,")).isEqualTo("coffee,negative space");
        assertThat(KnowledgeCaseService.normaliseTags(List.of(" "))).isNull();
    }

    @Test
    void approvalNeedsAnAnalysisAndALesson() {
        KnowledgeCase c = kase("a", "coffee", null);
        when(repository.findById("a")).thenReturn(Optional.of(c));
        assertThatThrownBy(() -> service.update("a", Map.of("status", "APPROVED")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approvedCasesRankBySectorAndTagOverlapThenNewest() {
        KnowledgeCase newest = kase("1", "transport", "trucks");
        KnowledgeCase coffee = kase("2", "coffee", "roastery,negative_space");
        KnowledgeCase older = kase("3", "bakery", null);
        when(repository.findByKindAndStatusOrderByCreatedAtDesc("logo", KnowledgeCase.APPROVED))
            .thenReturn(List.of(newest, coffee, older));

        List<KnowledgeCases.Case> cases = service.approved("logo", "Specialty coffee roastery", 2);

        assertThat(cases).extracting(KnowledgeCases.Case::title).containsExactly("2", "1");
        assertThat(cases.get(0).tags()).containsExactly("roastery", "negative_space");
    }

    @Test
    void appliesTheDraftWithoutOverwritingTheAdminsSector() throws Exception {
        KnowledgeCase c = kase("a", "coffee", null);
        service.applyDraft(c, "Here it is: {\"title\":\"Valve mark\",\"sector\":\"packaging\","
            + "\"tags\":[\"Jargon\",\"audience\"],\"analysis\":\"Draws a degassing valve.\",\"lesson\":\"Customers never think of packaging parts.\",}");
        assertThat(c.getTitle()).isEqualTo("Valve mark");
        assertThat(c.getSector()).isEqualTo("coffee");
        assertThat(c.getTags()).isEqualTo("jargon,audience");
        assertThat(c.getLesson()).startsWith("Customers never");
    }

    private static KnowledgeCase kase(String id, String sector, String tags) {
        KnowledgeCase c = new KnowledgeCase();
        c.setId(id);
        c.setTitle(id);
        c.setKind("logo");
        c.setVerdict(KnowledgeCase.GOOD);
        c.setSector(sector);
        c.setTags(tags);
        return c;
    }
}
