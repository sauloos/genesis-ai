package ai.genesisbrands.controller;

import ai.genesisbrands.agent.copy.BaselineCopyService;
import ai.genesisbrands.agent.copy.BaselineOutput;
import ai.genesisbrands.agent.copy.CopyAgent;
import ai.genesisbrands.agent.copy.CopyOutput;
import ai.genesisbrands.agent.copy.CopyRefinementLoop;
import ai.genesisbrands.agent.core.AgentRevision;
import ai.genesisbrands.agent.core.DirectionBrief;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agents/copy")
public class CopyAgentController {

    private final CopyAgent copyAgent;
    private final BaselineCopyService baselineCopyService;
    private final CopyRefinementLoop copyRefinementLoop;

    public CopyAgentController(CopyAgent copyAgent, BaselineCopyService baselineCopyService,
                                CopyRefinementLoop copyRefinementLoop) {
        this.copyAgent = copyAgent;
        this.baselineCopyService = baselineCopyService;
        this.copyRefinementLoop = copyRefinementLoop;
    }

    @PostMapping("/execute")
    public ResponseEntity<CopyOutput> execute(@RequestBody DirectionBrief brief) {
        return ResponseEntity.ok(copyAgent.execute(brief));
    }

    @PostMapping("/revise")
    public ResponseEntity<CopyOutput> revise(@RequestBody ReviseRequest request) {
        return ResponseEntity.ok(
            copyAgent.executeWithRevision(request.brief(), request.previous(), request.revision())
        );
    }

    @PostMapping("/compare")
    public ResponseEntity<CompareResult> compare(@RequestBody DirectionBrief brief) {
        long t0 = System.currentTimeMillis();
        CopyOutput genesisAi = copyAgent.execute(brief);
        long t1 = System.currentTimeMillis();
        BaselineOutput baselineAi = baselineCopyService.generate(brief);
        long t2 = System.currentTimeMillis();

        return ResponseEntity.ok(new CompareResult(genesisAi, baselineAi, t1 - t0, t2 - t1));
    }

    @PostMapping("/execute-with-evaluation")
    public ResponseEntity<CopyRefinementLoop.RefinementResult> executeWithEvaluation(@RequestBody DirectionBrief brief) {
        return ResponseEntity.ok(copyRefinementLoop.run(brief));
    }

    @PostMapping("/compare-with-evaluation")
    public ResponseEntity<CompareEvaluatedResult> compareWithEvaluation(@RequestBody DirectionBrief brief) {
        long t0 = System.currentTimeMillis();
        CopyRefinementLoop.RefinementResult genesisAi = copyRefinementLoop.run(brief);
        long t1 = System.currentTimeMillis();
        BaselineOutput baselineAi = baselineCopyService.generate(brief);
        long t2 = System.currentTimeMillis();

        return ResponseEntity.ok(new CompareEvaluatedResult(genesisAi, baselineAi, t1 - t0, t2 - t1));
    }

    public record ReviseRequest(
        DirectionBrief brief,
        CopyOutput previous,
        AgentRevision revision
    ) {}

    public record CompareResult(
        CopyOutput genesisAi,
        BaselineOutput baselineAi,
        long genesisAiMs,
        long baselineAiMs
    ) {}

    public record CompareEvaluatedResult(
        CopyRefinementLoop.RefinementResult genesisAi,
        BaselineOutput baselineAi,
        long genesisAiMs,
        long baselineAiMs
    ) {}
}
