package ai.genesisbrands.controller;

import ai.genesisbrands.agent.core.AgentRevision;
import ai.genesisbrands.agent.core.RefinementLoop;
import ai.genesisbrands.agent.purposeplaybook.PurposePlaybookAgent;
import ai.genesisbrands.agent.purposeplaybook.PurposePlaybookInput;
import ai.genesisbrands.agent.purposeplaybook.PurposePlaybookOutput;
import ai.genesisbrands.agent.purposeplaybook.PurposePlaybookRefinementLoop;
import ai.genesisbrands.model.QuestionnaireAnswer;
import ai.genesisbrands.model.QuestionnaireQuestion;
import ai.genesisbrands.model.QuestionnaireResponse;
import ai.genesisbrands.service.QuestionnaireResponseService;
import ai.genesisbrands.service.QuestionnaireService;
import ai.genesisbrands.service.QuestionnaireSignals;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * The Purpose Playbook agent's own backend, in its own module — same arrangement as every other
 * agent's controller.
 *
 * <p>The {@code /from-response} endpoints are the ones that matter. The raw {@code /execute} path
 * takes a signal map directly, which is right for the Playground and for a replay, but an
 * engagement's real input is a questionnaire response, and turning one into signals is not
 * something a caller should be reimplementing: answers are stored against question UUIDs, the
 * active-question set depends on the conditions, and the signal keys are the contract. Both
 * conversions go through {@link QuestionnaireSignals}, server-side, which is also the only place
 * the condition grammar is evaluated.
 *
 * <p>Admin-only, like every agent surface: {@code AdminAgentApiFilter} guards
 * {@code /api/agents/**}, and the public {@code X-Api-Key} does not count.
 */
@RestController
@RequestMapping("/api/agents/purpose-playbook")
public class PurposePlaybookAgentController {

    private final PurposePlaybookAgent agent;
    private final PurposePlaybookRefinementLoop refinementLoop;
    private final QuestionnaireService questionnaireService;
    private final QuestionnaireResponseService responseService;

    public PurposePlaybookAgentController(PurposePlaybookAgent agent,
                                          PurposePlaybookRefinementLoop refinementLoop,
                                          QuestionnaireService questionnaireService,
                                          QuestionnaireResponseService responseService) {
        this.agent = agent;
        this.refinementLoop = refinementLoop;
        this.questionnaireService = questionnaireService;
        this.responseService = responseService;
    }

    @PostMapping("/execute")
    public ResponseEntity<PurposePlaybookOutput> execute(@RequestBody PurposePlaybookInput input) {
        return ResponseEntity.ok(agent.execute(input));
    }

    @PostMapping("/revise")
    public ResponseEntity<PurposePlaybookOutput> revise(@RequestBody ReviseRequest request) {
        return ResponseEntity.ok(
            agent.executeWithRevision(request.input(), request.previous(), request.revision()));
    }

    @PostMapping("/execute-with-evaluation")
    public ResponseEntity<RefinementLoop.RefinementResult<PurposePlaybookOutput>> executeWithEvaluation(
            @RequestBody PurposePlaybookInput input) {
        return ResponseEntity.ok(refinementLoop.run(input));
    }

    /** One generation from a real questionnaire response, with no evaluation loop. */
    @PostMapping("/from-response/{responseId}")
    public ResponseEntity<PurposePlaybookOutput> fromResponse(@PathVariable String responseId,
                                                              @RequestBody(required = false) RunOptions options) {
        return ResponseEntity.ok(agent.execute(inputFor(responseId, options)));
    }

    /** The real path: generate, evaluate against the rubric, and revise until accepted. */
    @PostMapping("/from-response/{responseId}/with-evaluation")
    public ResponseEntity<RefinementLoop.RefinementResult<PurposePlaybookOutput>> fromResponseEvaluated(
            @PathVariable String responseId, @RequestBody(required = false) RunOptions options) {
        return ResponseEntity.ok(refinementLoop.run(inputFor(responseId, options)));
    }

    /**
     * What the agent reads, built from a stored response.
     *
     * <p>{@code expectedSignals} comes from the <em>active</em> questions, not all of them: a
     * question a condition gated away was never asked, and reporting it as unanswered would have
     * the playbook tell a client they skipped something they never saw.
     */
    private PurposePlaybookInput inputFor(String responseId, RunOptions options) {
        QuestionnaireResponse response;
        try {
            response = responseService.getResponse(responseId);
        } catch (NoSuchElementException e) {
            // A stale response id in the Playground is a 404, not a server fault — the view shows
            // the status, and a 500 there reads as "the agent broke".
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        List<QuestionnaireQuestion> questions =
            questionnaireService.listActiveQuestions(response.getQuestionnaireId(), responseId);
        List<QuestionnaireAnswer> answers = responseService.listAnswers(responseId);

        Map<String, Object> signals = QuestionnaireSignals.collect(questions, answers);
        List<String> expected = questions.stream()
            .map(QuestionnaireQuestion::getSignalKey)
            .filter(key -> key != null && !key.isBlank())
            .map(String::trim)
            .distinct()
            .toList();

        RunOptions opts = options == null ? new RunOptions(null, null, null) : options;
        String engagementId = opts.engagementId() == null || opts.engagementId().isBlank()
            ? "response-" + responseId
            : opts.engagementId();

        return new PurposePlaybookInput(engagementId, signals, expected,
            opts.trainingInstructions(), opts.additionalContext());
    }

    public record ReviseRequest(PurposePlaybookInput input, PurposePlaybookOutput previous,
                                AgentRevision revision) {}

    /** Everything a run needs that the response itself does not carry. All optional. */
    public record RunOptions(String engagementId, List<String> trainingInstructions,
                             String additionalContext) {}
}
