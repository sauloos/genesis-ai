package ai.genesisbrands.service;

import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.agent.core.DirectionBrief.CreativeDirection;
import ai.genesisbrands.agent.logo.LogoAssessment;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;

/**
 * What happens to the client's current logo, from the logoAssessment widget's answers
 * ({@code look.logoAssessment}, {@code look.logoDecision}, and {@code look.logoAssessedFile}, the
 * upload it assessed).
 *
 * <ul>
 *   <li><b>keep</b>: ANCHORED's logo is the client's own, unchanged; EVOLVED develops from it.</li>
 *   <li><b>evolve</b>: ANCHORED and EVOLVED are both briefed with the assessment, so both build on
 *       what it says to carry over, ANCHORED staying closest.</li>
 *   <li><b>fresh</b>, or no decision: the logo agents work as usual. The Purpose Playbook still
 *       reads the assessment as an answer.</li>
 * </ul>
 * DISRUPTIVE is never tied to the current logo.
 */
public record ClientLogoPlan(String decision, LogoAssessment assessment, String logoBlobPath) {

    static final String ASSESSMENT_SIGNAL = "look.logoAssessment";
    static final String DECISION_SIGNAL = "look.logoDecision";
    static final String FILE_SIGNAL = "look.logoAssessedFile";

    /** Empty unless the client read an assessment and chose keep or evolve. */
    static Optional<ClientLogoPlan> from(Map<String, Object> signals, ObjectMapper objectMapper) {
        Object decision = signals.get(DECISION_SIGNAL);
        Object raw = signals.get(ASSESSMENT_SIGNAL);
        if (!("keep".equals(decision) || "evolve".equals(decision)) || !(raw instanceof Map<?, ?>)) {
            return Optional.empty();
        }
        LogoAssessment assessment;
        try {
            assessment = objectMapper.convertValue(raw, LogoAssessment.class);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!(signals.get(FILE_SIGNAL) instanceof String url) || url.isBlank()) return Optional.empty();
        return Optional.of(new ClientLogoPlan((String) decision, assessment, QuestionnaireLogoAssessor.blobPath(url)));
    }

    /** True when this direction's logo is the client's own file rather than a new design. */
    boolean keepsLogoFor(CreativeDirection direction) {
        return "keep".equals(decision) && direction == CreativeDirection.ANCHORED;
    }

    /** The brief with the assessment added, for the directions that build on the current logo. */
    DirectionBrief brief(DirectionBrief brief) {
        boolean builds = brief.direction() == CreativeDirection.EVOLVED
            || (brief.direction() == CreativeDirection.ANCHORED && "evolve".equals(decision));
        boolean kept = keepsLogoFor(brief.direction());
        if (!builds && !kept) return brief;

        StringBuilder sb = new StringBuilder();
        if (brief.additionalContext() != null && !brief.additionalContext().isBlank()) {
            sb.append(brief.additionalContext().strip()).append("\n\n");
        }
        sb.append(assessment.renderForDesigner());
        if (kept) {
            sb.append("The client chose to KEEP this logo: it is this direction's logo as it is. "
                + "Build the palette, type and voice to sit with it.\n");
        } else if (brief.direction() == CreativeDirection.ANCHORED) {
            sb.append("The client chose to EVOLVE this logo. In this direction, stay closest to it: "
                + "keep what carries over and fix what must change, so a customer still recognises it.\n");
        } else {
            sb.append("Develop this direction's logo from the client's current one: keep what carries over, "
                + "change what must change, and go further than the anchored direction.\n");
        }
        return new DirectionBrief(brief.engagementId(), brief.brand(), brief.foundation(), brief.direction(),
            brief.trainingInstructions(), sb.toString(), brief.purpose());
    }
}
