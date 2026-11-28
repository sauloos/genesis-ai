package ai.genesisbrands.usage;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MeteredChatModelThinkingTest {

    private static Generation gen(String text, Map<String, Object> props) {
        return new Generation(AssistantMessage.builder().content(text).properties(props).build());
    }

    @Test
    void thinkingBlockBeforeTheAnswerIsDropped() {
        ChatResponse r = new ChatResponse(List.of(gen("", Map.of("signature", "abc")), gen("{\"ok\":true}", Map.of())));
        ChatResponse out = MeteredChatModel.withoutThinking(r);
        assertThat(out.getResult().getOutput().getText()).isEqualTo("{\"ok\":true}");
        assertThat(out.getResults()).hasSize(1);
    }

    @Test
    void redactedThinkingIsDroppedToo() {
        ChatResponse r = new ChatResponse(List.of(gen("", Map.of("data", "xyz")), gen("answer", Map.of())));
        assertThat(MeteredChatModel.withoutThinking(r).getResult().getOutput().getText()).isEqualTo("answer");
    }

    @Test
    void plainResponsesPassThroughUntouched() {
        ChatResponse single = new ChatResponse(List.of(gen("answer", Map.of())));
        assertThat(MeteredChatModel.withoutThinking(single)).isSameAs(single);
        ChatResponse twoText = new ChatResponse(List.of(gen("a", Map.of()), gen("b", Map.of())));
        assertThat(MeteredChatModel.withoutThinking(twoText)).isSameAs(twoText);
    }

    @Test
    void aThinkingOnlyResponseIsLeftAlone() {
        ChatResponse only = new ChatResponse(List.of(gen("", Map.of("signature", "abc")), gen("", Map.of("signature", "def"))));
        assertThat(MeteredChatModel.withoutThinking(only)).isSameAs(only);
    }
}
