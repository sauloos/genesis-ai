package ai.genesisbrands.agent.purposeplaybook;

import java.util.List;
import java.util.Map;

/**
 * What the Purpose Playbook agent runs on: a completed discovery questionnaire, keyed by Layer 1
 * signal key rather than by question id.
 *
 * <p>There is deliberately no {@code DirectionBrief} here. Every other brand agent takes one,
 * because every other brand agent runs after the directions have been derived. This agent
 * produces the document the directions are derived <em>from</em>, so a brief would be circular.
 *
 * @param engagementId     the owning engagement, or a playground run id
 * @param signals          answered signals, in question order, values keeping their JSON shape
 *                         (see {@code QuestionnaireSignals.collect})
 * @param expectedSignals  the signal keys the questionnaire actually asked for on this response
 *                         (its <em>active</em> questions). Without this, a key missing from
 *                         {@code signals} is ambiguous: skipped by the client, or never asked
 *                         because a condition gated it away. The confidence map depends on
 *                         telling those apart.
 * @param trainingInstructions  facilitator instructions for this engagement, if any
 * @param additionalContext     free text appended to the prompt
 */
public record PurposePlaybookInput(
    String engagementId,
    Map<String, Object> signals,
    List<String> expectedSignals,
    List<String> trainingInstructions,
    String additionalContext
) {

    public PurposePlaybookInput {
        signals = signals == null ? Map.of() : signals;
        expectedSignals = expectedSignals == null ? List.of() : expectedSignals;
        trainingInstructions = trainingInstructions == null ? List.of() : trainingInstructions;
    }

    /** A signal's value, or null when it was not answered. */
    public Object signal(String key) {
        return signals.get(key);
    }

    /** A signal's value as text, or the empty string — for the few places that want a plain string. */
    public String text(String key) {
        Object value = signals.get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
