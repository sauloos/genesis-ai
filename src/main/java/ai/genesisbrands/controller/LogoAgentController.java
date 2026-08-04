package ai.genesisbrands.controller;

import ai.genesisbrands.agent.core.AgentRevision;
import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.agent.logo.BaselineLogoOutput;
import ai.genesisbrands.agent.logo.BaselineLogoService;
import ai.genesisbrands.agent.logo.LogoAgent;
import ai.genesisbrands.agent.logo.LogoOutput;
import ai.genesisbrands.agent.logo.LogoRefinementLoop;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agents/logo")
public class LogoAgentController {

    private final LogoAgent logoAgent;
    private final BaselineLogoService baselineLogoService;
    private final LogoRefinementLoop logoRefinementLoop;

    public LogoAgentController(LogoAgent logoAgent, BaselineLogoService baselineLogoService,
                                LogoRefinementLoop logoRefinementLoop) {
        this.logoAgent = logoAgent;
        this.baselineLogoService = baselineLogoService;
        this.logoRefinementLoop = logoRefinementLoop;
    }

    @PostMapping("/execute")
    public ResponseEntity<LogoOutput> execute(@RequestBody ExecuteRequest request) {
        return ResponseEntity.ok(logoAgent.execute(request.brief(), request.method()));
    }

    @PostMapping("/revise")
    public ResponseEntity<LogoOutput> revise(@RequestBody ReviseRequest request) {
        return ResponseEntity.ok(
            logoAgent.executeWithRevision(request.brief(), request.previous(), request.revision())
        );
    }

    @PostMapping("/compare")
    public ResponseEntity<CompareResult> compare(@RequestBody ExecuteRequest request) {
        long t0 = System.currentTimeMillis();
        LogoOutput genesisAi = logoAgent.execute(request.brief(), request.method());
        long t1 = System.currentTimeMillis();
        BaselineLogoOutput baselineAi = baselineLogoService.generate(request.brief(), request.method());
        long t2 = System.currentTimeMillis();

        return ResponseEntity.ok(new CompareResult(genesisAi, baselineAi, t1 - t0, t2 - t1));
    }

    @PostMapping("/execute-with-evaluation")
    public ResponseEntity<LogoRefinementLoop.RefinementResult> executeWithEvaluation(@RequestBody ExecuteRequest request) {
        return ResponseEntity.ok(logoRefinementLoop.run(request.brief(), request.method()));
    }

    @PostMapping("/compare-with-evaluation")
    public ResponseEntity<CompareEvaluatedResult> compareWithEvaluation(@RequestBody ExecuteRequest request) {
        long t0 = System.currentTimeMillis();
        LogoRefinementLoop.RefinementResult genesisAi = logoRefinementLoop.run(request.brief(), request.method());
        long t1 = System.currentTimeMillis();
        BaselineLogoOutput baselineAi = baselineLogoService.generate(request.brief(), request.method());
        long t2 = System.currentTimeMillis();

        return ResponseEntity.ok(new CompareEvaluatedResult(genesisAi, baselineAi, t1 - t0, t2 - t1));
    }

    public record ExecuteRequest(DirectionBrief brief, LogoOutput.Method method) {
        public ExecuteRequest {
            if (method == null) method = LogoOutput.Method.DALLE;
        }
    }

    public record ReviseRequest(
        DirectionBrief brief,
        LogoOutput previous,
        AgentRevision revision
    ) {}

    public record CompareResult(
        LogoOutput genesisAi,
        BaselineLogoOutput baselineAi,
        long genesisAiMs,
        long baselineAiMs
    ) {}

    public record CompareEvaluatedResult(
        LogoRefinementLoop.RefinementResult genesisAi,
        BaselineLogoOutput baselineAi,
        long genesisAiMs,
        long baselineAiMs
    ) {}
}
