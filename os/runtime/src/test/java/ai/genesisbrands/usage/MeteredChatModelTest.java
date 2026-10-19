package ai.genesisbrands.usage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeteredChatModelTest {

    private final ChatModel delegate = mock(ChatModel.class);
    private final UsageEventRepository repo = mock(UsageEventRepository.class);
    private final MeteredChatModel model = new MeteredChatModel(delegate, new UsageService(repo, new UsagePricing()));

    @AfterEach
    void clear() {
        UsageContext.clearAgentHint();
    }

    private static ChatResponse response(String model, Integer in, Integer out) {
        return ChatResponse.builder()
            .generations(List.of(new Generation(new AssistantMessage("ok"))))
            .metadata(ChatResponseMetadata.builder().model(model).usage(new DefaultUsage(in, out)).build())
            .build();
    }

    private UsageEvent saved() {
        ArgumentCaptor<UsageEvent> captor = ArgumentCaptor.forClass(UsageEvent.class);
        verify(repo).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void recordsCostAndAttributionForACall() {
        when(delegate.call(any(Prompt.class))).thenReturn(response("claude-sonnet-5-20260601", 1_000, 2_000));
        UsageContext.noteAgent("copy-evaluator");

        UsageContext.callWith(UsageScope.of("client-1", null, "/api/client/regenerate", null),
            () -> model.call(new Prompt("hi")));

        UsageEvent e = saved();
        assertThat(e.getModel()).isEqualTo("claude-sonnet-5-20260601");
        assertThat(e.getInputTokens()).isEqualTo(1_000);
        assertThat(e.getOutputTokens()).isEqualTo(2_000);
        // 1,000 × $3/Mtok + 2,000 × $15/Mtok = $0.033
        assertThat(e.getCostMicros()).isEqualTo(33_000);
        assertThat(e.getClientUserId()).isEqualTo("client-1");
        assertThat(e.getAgentId()).isEqualTo("copy-evaluator");
        assertThat(e.getSource()).isEqualTo("/api/client/regenerate");
        assertThat(UsageContext.agentHint()).as("the hint is consumed by the call it names").isNull();
    }

    @Test
    void explicitScopeAgentWinsOverTheHint() {
        when(delegate.call(any(Prompt.class))).thenReturn(response("claude-opus-5", 10, 10));
        UsageContext.noteAgent("copy");

        UsageContext.callWith(UsageScope.of(null, "client-pulse", "standing", "run-9"), () -> model.call(new Prompt("hi")));

        UsageEvent e = saved();
        assertThat(e.getAgentId()).isEqualTo("client-pulse");
        assertThat(e.getRunId()).isEqualTo("run-9");
        assertThat(e.getClientUserId()).isNull();
    }

    @Test
    void streamRecordsTheLargestUsageSeenOnceItCompletes() {
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.just(
            response("claude-opus-5", 500, 1), response("claude-opus-5", 0, 40), response("claude-opus-5", 500, 120)));

        Flux<ChatResponse> stream = UsageContext.callWith(UsageScope.of("client-2", null, "/api/consultant/chat", null),
            () -> model.stream(new Prompt("hi")));
        verify(repo, never()).save(any());
        stream.blockLast();

        UsageEvent e = saved();
        assertThat(e.getInputTokens()).isEqualTo(500);
        assertThat(e.getOutputTokens()).isEqualTo(120);
        assertThat(e.getClientUserId()).isEqualTo("client-2");
    }

    @Test
    void aLedgerFailureNeverBreaksTheCall() {
        when(delegate.call(any(Prompt.class))).thenReturn(response("claude-haiku-4-5", 1, 1));
        when(repo.save(any())).thenThrow(new IllegalStateException("db down"));

        assertThat(model.call(new Prompt("hi")).getResult().getOutput().getText()).isEqualTo("ok");
    }
}
