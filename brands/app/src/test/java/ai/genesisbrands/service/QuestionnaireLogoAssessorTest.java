package ai.genesisbrands.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionnaireLogoAssessorTest {

    @Test
    void findsEveryUploadedFileInOrder() {
        assertThat(QuestionnaireLogoAssessor.logoUrls(Map.of("blobPath", "/api/assets/a.png")))
            .containsExactly("/api/assets/a.png");
        assertThat(QuestionnaireLogoAssessor.logoUrls(List.of(
            Map.of("blobPath", "/api/assets/a.ai"), Map.of("name", "no path"), Map.of("blobPath", "/api/assets/b.png"))))
            .containsExactly("/api/assets/a.ai", "/api/assets/b.png");
        assertThat(QuestionnaireLogoAssessor.logoUrls(null)).isEmpty();
        assertThat(QuestionnaireLogoAssessor.logoUrls("a string")).isEmpty();
        assertThat(QuestionnaireLogoAssessor.logoUrls(Map.of("blobPath", " "))).isEmpty();
    }

    @Test
    void stripsTheAssetPrefixOnly() {
        assertThat(QuestionnaireLogoAssessor.blobPath("/api/assets/uploads/x.png")).isEqualTo("uploads/x.png");
        assertThat(QuestionnaireLogoAssessor.blobPath("uploads/x.png")).isEqualTo("uploads/x.png");
    }

    @Test
    void brandContextKeepsTextAnswersAndLeavesTheLogoOut() {
        Map<String, Object> signals = new LinkedHashMap<>();
        signals.put("brand.name", "One Way");
        signals.put("brand.values", List.of("honest", "direct"));
        signals.put("brand.age", 12);
        signals.put("look.currentLogo", Map.of("blobPath", "/api/assets/a.png"));
        signals.put("look.currentLogoFeelings", "Love the arrow");
        signals.put("look.logoIntent", "evolve it");
        signals.put("look.existingAssets", List.of(Map.of("blobPath", "/api/assets/b.pdf")));
        signals.put("blank", "  ");

        String context = QuestionnaireLogoAssessor.brandContext(signals);

        assertThat(context).isEqualTo("brand.name: One Way\nbrand.values: honest; direct\nbrand.age: 12\n");
    }

    @Test
    void brandContextIsCappedAndNullWhenEmpty() {
        Map<String, Object> signals = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) signals.put("k" + i, "x".repeat(200));

        assertThat(QuestionnaireLogoAssessor.brandContext(signals))
            .hasSizeLessThanOrEqualTo(QuestionnaireLogoAssessor.CONTEXT_CHARS);
        assertThat(QuestionnaireLogoAssessor.brandContext(Map.of("look.currentLogo", "x"))).isNull();
    }
}
