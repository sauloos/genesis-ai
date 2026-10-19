package ai.genesisbrands.usage;

import java.util.function.Supplier;

/**
 * Thread-bound {@link UsageScope}, read by {@link MeteredChatModel} when it records a call.
 * Request threads get it from {@link UsageScopeInterceptor}, {@code @Async} work inherits it
 * through {@link UsageTaskDecorator}, and background runs (schedulers, standing agents) set it
 * explicitly with {@link #callWith}.
 *
 * <p>Separately it keeps an agent hint: the agent config key most recently resolved on this
 * thread ({@code AgentProperties.get}). Agents read their config right before each model call,
 * so the hint names the caller without every agent having to declare itself. An explicit
 * {@code agentId} in the scope wins over the hint.
 */
public final class UsageContext {

    private static final ThreadLocal<UsageScope> SCOPE = new ThreadLocal<>();
    private static final ThreadLocal<String> AGENT_HINT = new ThreadLocal<>();

    private UsageContext() {}

    public static UsageScope current() {
        return SCOPE.get();
    }

    public static <T> T callWith(UsageScope scope, Supplier<T> work) {
        UsageScope previous = SCOPE.get();
        SCOPE.set(scope);
        try {
            return work.get();
        } finally {
            restore(previous);
        }
    }

    public static void runWith(UsageScope scope, Runnable work) {
        callWith(scope, () -> { work.run(); return null; });
    }

    static void set(UsageScope scope) {
        SCOPE.set(scope);
    }

    static void restore(UsageScope previous) {
        if (previous == null) SCOPE.remove(); else SCOPE.set(previous);
    }

    public static void noteAgent(String configKey) {
        AGENT_HINT.set(configKey);
    }

    static String agentHint() {
        return AGENT_HINT.get();
    }

    static void clearAgentHint() {
        AGENT_HINT.remove();
    }
}
