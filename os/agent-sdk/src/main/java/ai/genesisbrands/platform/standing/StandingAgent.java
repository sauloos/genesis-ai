package ai.genesisbrands.platform.standing;

import ai.genesisbrands.platform.AgentCustomOption;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * An agent that keeps working for a client unattended: on a schedule, inside the client's own
 * context, with everything it produces waiting in the client's inbox for approval. An agent
 * module opts in by implementing this next to {@code CoreAgent}; the standing runtime collects
 * every such bean, so the agent becomes subscribable with no runtime wiring.
 * <p>
 * Admins decide whether the agent is offered and within which limits; the client chooses the
 * cadence and fills in {@link #subscriptionOptions()}.
 */
public interface StandingAgent {

    /** Same id as the agent's {@code CoreAgent}. */
    String agentId();

    default Set<TriggerKind> supportedTriggers() {
        return EnumSet.of(TriggerKind.SCHEDULE);
    }

    /**
     * What one event carries, for an agent whose {@link #supportedTriggers()} include
     * {@link TriggerKind#EVENT}: the client fills these in (the subscription widget renders them
     * as a form) or sends them through a connector. Values travel as strings, like options.
     */
    default List<AgentCustomOption> eventFields() {
        return List.of();
    }

    /** The action that sends an event, as the client sees it, e.g. "Add a lead". */
    default String eventLabel() {
        return "Send";
    }

    /** What the client configures. Rendered by the subscription widget and the admin page. */
    default List<AgentCustomOption> subscriptionOptions() {
        return List.of();
    }

    /** Cadences this agent makes sense at. The admin may narrow it further, never widen it. */
    default Set<Cadence> allowedCadences() {
        return EnumSet.allOf(Cadence.class);
    }

    default Cadence defaultCadence() {
        return Cadence.WEEKLY;
    }

    /**
     * One unattended run, on a schedule, by hand, or for one {@link StandingRunContext#event()}.
     * Returns drafts and reports for the inbox; it must never publish,
     * send or change anything outside its own result. Throwing fails the run.
     */
    StandingRunResult run(StandingRunContext context);
}
