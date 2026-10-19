package ai.genesisbrands.usage;

import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wraps the primary {@link ChatModel} so every chat call, from any agent or service, lands in
 * the usage ledger. Attribution comes from {@link UsageContext} on the calling thread; a stream
 * captures it at call time because its chunks arrive on other threads.
 */
public class MeteredChatModel implements ChatModel {

    private final ChatModel delegate;
    private final UsageService usage;

    public MeteredChatModel(ChatModel delegate, UsageService usage) {
        this.delegate = delegate;
        this.usage = usage;
    }

    public ChatModel delegate() {
        return delegate;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        UsageScope scope = pinnedScope();
        String hint = takeAgentHint();
        ChatResponse response = delegate.call(prompt);
        Usage u = usageOf(response);
        if (u != null) {
            usage.recordChat(modelOf(response, prompt), tokens(u.getPromptTokens()), tokens(u.getCompletionTokens()), scope, hint);
        }
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        UsageScope scope = pinnedScope();
        String hint = takeAgentHint();
        // Anthropic reports input tokens on the first event and the running output count on
        // later ones, so the largest value seen for each is the call's total.
        AtomicLong in = new AtomicLong();
        AtomicLong out = new AtomicLong();
        AtomicReference<String> model = new AtomicReference<>();
        return delegate.stream(prompt)
            .doOnNext(r -> {
                Usage u = usageOf(r);
                if (u != null) {
                    in.accumulateAndGet(tokens(u.getPromptTokens()), Math::max);
                    out.accumulateAndGet(tokens(u.getCompletionTokens()), Math::max);
                }
                String m = r.getMetadata() == null ? null : r.getMetadata().getModel();
                if (m != null && !m.isBlank()) model.set(m);
            })
            .doFinally(signal -> {
                if (in.get() > 0 || out.get() > 0) {
                    usage.recordChat(model.get() != null ? model.get() : optionsModel(prompt), in.get(), out.get(), scope, hint);
                }
            });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    // Resolved on the calling thread: a request scope's client lookup needs the live request.
    private static UsageScope pinnedScope() {
        UsageScope scope = UsageContext.current();
        return scope == null ? null : scope.pinned();
    }

    private static String takeAgentHint() {
        String hint = UsageContext.agentHint();
        UsageContext.clearAgentHint();
        return hint;
    }

    private static Usage usageOf(ChatResponse response) {
        ChatResponseMetadata md = response == null ? null : response.getMetadata();
        return md == null ? null : md.getUsage();
    }

    private String modelOf(ChatResponse response, Prompt prompt) {
        String m = response.getMetadata() == null ? null : response.getMetadata().getModel();
        return m != null && !m.isBlank() ? m : optionsModel(prompt);
    }

    private String optionsModel(Prompt prompt) {
        if (prompt.getOptions() != null && prompt.getOptions().getModel() != null) return prompt.getOptions().getModel();
        ChatOptions defaults = delegate.getDefaultOptions();
        return defaults == null ? null : defaults.getModel();
    }

    private static long tokens(Integer n) {
        return n == null ? 0 : n;
    }
}
