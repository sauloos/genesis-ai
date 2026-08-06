package ai.genesisbrands.controller;

import ai.genesisbrands.agent.core.AgentRevision;
import ai.genesisbrands.agent.playbook.BaselineOutput;
import ai.genesisbrands.agent.playbook.BaselinePlaybookService;
import ai.genesisbrands.agent.playbook.PlaybookAgent;
import ai.genesisbrands.agent.playbook.PlaybookInput;
import ai.genesisbrands.agent.playbook.PlaybookOutput;
import ai.genesisbrands.agent.playbook.PlaybookRefinementLoop;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agents/playbook")
public class PlaybookAgentController {

    private final PlaybookAgent playbookAgent;
    private final BaselinePlaybookService baselinePlaybookService;
    private final PlaybookRefinementLoop playbookRefinementLoop;

    public PlaybookAgentController(PlaybookAgent playbookAgent, BaselinePlaybookService baselinePlaybookService,
                                    PlaybookRefinementLoop playbookRefinementLoop) {
        this.playbookAgent = playbookAgent;
        this.baselinePlaybookService = baselinePlaybookService;
        this.playbookRefinementLoop = playbookRefinementLoop;
    }

    @PostMapping("/execute")
    public ResponseEntity<PlaybookOutput> execute(@RequestBody PlaybookInput input) {
        return ResponseEntity.ok(playbookAgent.execute(input));
    }

    @PostMapping("/revise")
    public ResponseEntity<PlaybookOutput> revise(@RequestBody ReviseRequest request) {
        return ResponseEntity.ok(
            playbookAgent.executeWithRevision(request.input(), request.previous(), request.revision())
        );
    }

    @PostMapping("/compare")
    public ResponseEntity<CompareResult> compare(@RequestBody PlaybookInput input) {
        long t0 = System.currentTimeMillis();
        PlaybookOutput genesisAi = playbookAgent.execute(input);
        long t1 = System.currentTimeMillis();
        BaselineOutput baselineAi = baselinePlaybookService.generate(input);
        long t2 = System.currentTimeMillis();

        return ResponseEntity.ok(new CompareResult(genesisAi, baselineAi, t1 - t0, t2 - t1));
    }

    @PostMapping("/execute-with-evaluation")
    public ResponseEntity<PlaybookRefinementLoop.RefinementResult> executeWithEvaluation(@RequestBody PlaybookInput input) {
        return ResponseEntity.ok(playbookRefinementLoop.run(input));
    }

    @PostMapping("/compare-with-evaluation")
    public ResponseEntity<CompareEvaluatedResult> compareWithEvaluation(@RequestBody PlaybookInput input) {
        long t0 = System.currentTimeMillis();
        PlaybookRefinementLoop.RefinementResult genesisAi = playbookRefinementLoop.run(input);
        long t1 = System.currentTimeMillis();
        BaselineOutput baselineAi = baselinePlaybookService.generate(input);
        long t2 = System.currentTimeMillis();

        return ResponseEntity.ok(new CompareEvaluatedResult(genesisAi, baselineAi, t1 - t0, t2 - t1));
    }

    public record ReviseRequest(
        PlaybookInput input,
        PlaybookOutput previous,
        AgentRevision revision
    ) {}

    public record CompareResult(
        PlaybookOutput genesisAi,
        BaselineOutput baselineAi,
        long genesisAiMs,
        long baselineAiMs
    ) {}

    public record CompareEvaluatedResult(
        PlaybookRefinementLoop.RefinementResult genesisAi,
        BaselineOutput baselineAi,
        long genesisAiMs,
        long baselineAiMs
    ) {}
}
