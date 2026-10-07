package ai.genesisbrands.controller;

import ai.genesisbrands.service.GenericSingleShotAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Admin/Playground facade for the generic CREATE-shaped core agents (Summarizer, RFP
 * Response, Research & Synthesis, Writer) — stateless, with no live client context
 * (mirrors every existing specialist's test endpoint). Playground persists its own run
 * history via the already-generic {@code /api/playground/sessions} surface, so this
 * controller does nothing beyond invoking the model.
 */
@RestController
@RequestMapping("/api/agent-generation")
@RequiredArgsConstructor
@Tag(name = "Agent Generation", description = "Stateless single-shot generation for CREATE-shaped generic core agents")
public class AgentGenerationController {

    private final GenericSingleShotAgentService generationService;

    @PostMapping("/{agentId}/generate")
    @Operation(summary = "Run a generic CREATE agent once, with no live client context")
    public GenerateResponse generate(@PathVariable String agentId, @RequestBody GenerateRequest req) {
        String output = generationService.generate(agentId, req.input(), null);
        return new GenerateResponse(output);
    }

    public record GenerateRequest(String input) {}

    public record GenerateResponse(String output) {}
}
