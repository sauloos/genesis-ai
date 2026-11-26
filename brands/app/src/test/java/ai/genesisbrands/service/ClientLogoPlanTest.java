package ai.genesisbrands.service;

import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.agent.core.DirectionBrief.CreativeDirection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ClientLogoPlanTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static Map<String, Object> signals(String decision) {
        Map<String, Object> s = new HashMap<>();
        s.put("look.logoDecision", decision);
        s.put("look.logoAssessedFile", "/api/assets/uploads/logo.png");
        s.put("look.logoAssessment", Map.of(
            "score", 62, "verdict", "EVOLVE", "headline", "Strong symbol, weak lettering",
            "keep", List.of("The leaf symbol"), "change", List.of("The script wordmark")));
        return s;
    }

    private static DirectionBrief brief(CreativeDirection direction) {
        return new DirectionBrief("e1", null, null, direction, null, "Existing context.", null);
    }

    @Test
    void keepUsesTheClientsFileForAnchoredOnly() {
        ClientLogoPlan plan = ClientLogoPlan.from(signals("keep"), objectMapper).orElseThrow();

        assertThat(plan.logoBlobPath()).isEqualTo("uploads/logo.png");
        assertThat(plan.keepsLogoFor(CreativeDirection.ANCHORED)).isTrue();
        assertThat(plan.keepsLogoFor(CreativeDirection.EVOLVED)).isFalse();
        assertThat(plan.keepsLogoFor(CreativeDirection.DISRUPTIVE)).isFalse();
    }

    @Test
    void evolveKeepsNoLogoAsIs() {
        ClientLogoPlan plan = ClientLogoPlan.from(signals("evolve"), objectMapper).orElseThrow();

        for (CreativeDirection d : CreativeDirection.values()) {
            assertThat(plan.keepsLogoFor(d)).isFalse();
        }
    }

    @Test
    void briefsAnchoredAndEvolvedButNeverDisruptive() {
        for (String decision : List.of("keep", "evolve")) {
            ClientLogoPlan plan = ClientLogoPlan.from(signals(decision), objectMapper).orElseThrow();

            String anchored = plan.brief(brief(CreativeDirection.ANCHORED)).additionalContext();
            String evolved = plan.brief(brief(CreativeDirection.EVOLVED)).additionalContext();
            DirectionBrief disruptive = brief(CreativeDirection.DISRUPTIVE);

            assertThat(anchored).startsWith("Existing context.").contains("The leaf symbol");
            assertThat(anchored).contains("keep".equals(decision) ? "KEEP" : "EVOLVE");
            assertThat(evolved).contains("The script wordmark").contains("go further");
            assertThat(plan.brief(disruptive)).isSameAs(disruptive);
        }
    }

    @Test
    void noPlanForFreshMissingDecisionOrMissingFile() {
        assertThat(ClientLogoPlan.from(signals("fresh"), objectMapper)).isEmpty();
        assertThat(ClientLogoPlan.from(Map.of(), objectMapper)).isEmpty();

        Map<String, Object> noFile = signals("keep");
        noFile.remove("look.logoAssessedFile");
        assertThat(ClientLogoPlan.from(noFile, objectMapper)).isEmpty();

        Map<String, Object> notAMap = signals("keep");
        notAMap.put("look.logoAssessment", "a string");
        assertThat(ClientLogoPlan.from(notAMap, objectMapper)).isEqualTo(Optional.empty());
    }
}
