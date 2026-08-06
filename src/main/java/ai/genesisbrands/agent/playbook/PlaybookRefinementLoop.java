package ai.genesisbrands.agent.playbook;

import ai.genesisbrands.agent.config.AgentProperties;
import ai.genesisbrands.agent.core.EvaluationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * The Genesis AI evaluation loop for the Playbook Assembly Agent: dispatch to the
 * specialist, have Genesis AI (the creative director) evaluate the draft against the
 * playbook rubric, and either accept it or send it back for revision — up to the
 * agent's configured max-iterations budget.
 */
@Service
public class PlaybookRefinementLoop {

    private static final Logger log = LoggerFactory.getLogger(PlaybookRefinementLoop.class);
    private static final String AGENT_ID = "playbook";

    private final PlaybookAgent playbookAgent;
    private final PlaybookEvaluator playbookEvaluator;
    private final AgentProperties agentProperties;

    public PlaybookRefinementLoop(PlaybookAgent playbookAgent, PlaybookEvaluator playbookEvaluator, AgentProperties agentProperties) {
        this.playbookAgent = playbookAgent;
        this.playbookEvaluator = playbookEvaluator;
        this.agentProperties = agentProperties;
    }

    public RefinementResult run(PlaybookInput input) {
        int maxIterations = agentProperties.get(AGENT_ID).getMaxIterations();

        PlaybookOutput current = playbookAgent.execute(input);
        List<EvaluationRound> rounds = new ArrayList<>();

        for (int i = 1; i <= maxIterations; i++) {
            EvaluationResult evaluation = playbookEvaluator.evaluate(input, current);
            rounds.add(new EvaluationRound(i, evaluation));

            if (evaluation.verdict() == EvaluationResult.Verdict.ACCEPT) {
                return new RefinementResult(current, true, rounds);
            }

            if (i == maxIterations) {
                log.warn("PlaybookRefinementLoop exhausted {} iterations without ACCEPT for engagement {}",
                    maxIterations, input.brief().engagementId());
                break;
            }

            current = playbookAgent.executeWithRevision(input, current, evaluation.revision());
        }

        return new RefinementResult(current, false, rounds);
    }

    public record EvaluationRound(int iteration, EvaluationResult evaluation) {}

    public record RefinementResult(PlaybookOutput output, boolean accepted, List<EvaluationRound> rounds) {}
}
