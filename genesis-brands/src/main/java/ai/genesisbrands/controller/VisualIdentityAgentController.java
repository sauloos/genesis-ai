package ai.genesisbrands.controller;

import ai.genesisbrands.agent.core.AgentRevision;
import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.agent.visualidentity.BaselineVisualIdentityOutput;
import ai.genesisbrands.agent.visualidentity.BaselineVisualIdentityService;
import ai.genesisbrands.agent.visualidentity.VisualIdentityAgent;
import ai.genesisbrands.agent.visualidentity.VisualIdentityOutput;
import ai.genesisbrands.agent.visualidentity.VisualIdentityRefinementLoop;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agents/visual-identity")
public class VisualIdentityAgentController {

    private final VisualIdentityAgent visualIdentityAgent;
    private final BaselineVisualIdentityService baselineVisualIdentityService;
    private final VisualIdentityRefinementLoop visualIdentityRefinementLoop;

    public VisualIdentityAgentController(VisualIdentityAgent visualIdentityAgent,
                                          BaselineVisualIdentityService baselineVisualIdentityService,
                                          VisualIdentityRefinementLoop visualIdentityRefinementLoop) {
        this.visualIdentityAgent = visualIdentityAgent;
        this.baselineVisualIdentityService = baselineVisualIdentityService;
        this.visualIdentityRefinementLoop = visualIdentityRefinementLoop;
    }

    @PostMapping("/execute")
    public ResponseEntity<VisualIdentityOutput> execute(@RequestBody DirectionBrief brief) {
        return ResponseEntity.ok(visualIdentityAgent.execute(brief));
    }

    @PostMapping("/revise")
    public ResponseEntity<VisualIdentityOutput> revise(@RequestBody ReviseRequest request) {
        return ResponseEntity.ok(
            visualIdentityAgent.executeWithRevision(request.brief(), request.previous(), request.revision())
        );
    }

    @PostMapping("/compare")
    public ResponseEntity<CompareResult> compare(@RequestBody DirectionBrief brief) {
        long t0 = System.currentTimeMillis();
        VisualIdentityOutput genesisAi = visualIdentityAgent.execute(brief);
        long t1 = System.currentTimeMillis();
        BaselineVisualIdentityOutput baselineAi = baselineVisualIdentityService.generate(brief);
        long t2 = System.currentTimeMillis();

        return ResponseEntity.ok(new CompareResult(genesisAi, baselineAi, t1 - t0, t2 - t1));
    }

    @PostMapping("/execute-with-evaluation")
    public ResponseEntity<VisualIdentityRefinementLoop.RefinementResult> executeWithEvaluation(@RequestBody DirectionBrief brief) {
        return ResponseEntity.ok(visualIdentityRefinementLoop.run(brief));
    }

    @PostMapping("/compare-with-evaluation")
    public ResponseEntity<CompareEvaluatedResult> compareWithEvaluation(@RequestBody DirectionBrief brief) {
        long t0 = System.currentTimeMillis();
        VisualIdentityRefinementLoop.RefinementResult genesisAi = visualIdentityRefinementLoop.run(brief);
        long t1 = System.currentTimeMillis();
        BaselineVisualIdentityOutput baselineAi = baselineVisualIdentityService.generate(brief);
        long t2 = System.currentTimeMillis();

        return ResponseEntity.ok(new CompareEvaluatedResult(genesisAi, baselineAi, t1 - t0, t2 - t1));
    }

    public record ReviseRequest(
        DirectionBrief brief,
        VisualIdentityOutput previous,
        AgentRevision revision
    ) {}

    public record CompareResult(
        VisualIdentityOutput genesisAi,
        BaselineVisualIdentityOutput baselineAi,
        long genesisAiMs,
        long baselineAiMs
    ) {}

    public record CompareEvaluatedResult(
        VisualIdentityRefinementLoop.RefinementResult genesisAi,
        BaselineVisualIdentityOutput baselineAi,
        long genesisAiMs,
        long baselineAiMs
    ) {}
}
