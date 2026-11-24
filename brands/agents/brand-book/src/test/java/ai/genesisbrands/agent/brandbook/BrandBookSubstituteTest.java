package ai.genesisbrands.agent.brandbook;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BrandBookSubstituteTest {

    @Test
    void fillsEveryOccurrenceOfEachToken() {
        Map<String, String> subs = Map.of("{{NAME}}", "One Way", "{{PHOTO_IMAGERY_1_DATA}}", "data:x");
        assertThat(BrandBookTemplateRenderer.substitute(
            "<h1>{{NAME}}</h1><img src=\"{{PHOTO_IMAGERY_1_DATA}}\"><p>{{NAME}}</p>", subs))
            .isEqualTo("<h1>One Way</h1><img src=\"data:x\"><p>One Way</p>");
    }

    @Test
    void leavesUnknownTokensAndBlanksNullValues() {
        Map<String, String> subs = new HashMap<>();
        subs.put("{{EMPTY}}", null);
        assertThat(BrandBookTemplateRenderer.substitute("a{{EMPTY}}b{{UNKNOWN}}c", subs))
            .isEqualTo("ab{{UNKNOWN}}c");
    }

    @Test
    void neverReadsTokensOutOfASubstitutedValue() {
        // Client copy that happens to contain a token is printed, not expanded.
        Map<String, String> subs = Map.of("{{STORY}}", "say {{NAME}}", "{{NAME}}", "One Way");
        assertThat(BrandBookTemplateRenderer.substitute("{{STORY}} / {{NAME}}", subs))
            .isEqualTo("say {{NAME}} / One Way");
    }
}
