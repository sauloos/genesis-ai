package ai.genesisbrands.platform;

import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.model.QuestionnaireAnswer;
import ai.genesisbrands.model.QuestionnaireQuestion;

import java.util.List;

/**
 * Extension point: a tenant with its own brand-direction methodology (e.g. Genesis
 * Brands' anchored/evolved/disruptive briefs) implements this to power Playground's
 * "derive from questionnaire" flow. Core stays ignorant of the methodology — a bare
 * platform instance with no bean registered simply has no brief derivation available.
 */
public interface BriefDerivationExtension {

    List<DirectionBrief> derive(String engagementId, List<QuestionnaireQuestion> questions, List<QuestionnaireAnswer> answers);
}
