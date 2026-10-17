package ai.genesisbrands.connector.agent;

import ai.genesisbrands.connector.auth.ConnectorPrincipal;
import ai.genesisbrands.model.GeneratedDocument;
import ai.genesisbrands.platform.connector.ConnectorJob;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** REST face of the Agent connectors. The MCP face ({@code /mcp/agents}) calls the same service. */
@RestController
@RequestMapping("/api/connect/agents")
@RequiredArgsConstructor
@Tag(name = "Agent Connectors", description = "Agents enabled for your account — authenticate with a client connector key")
public class AgentConnectorController {

    private final AgentConnectorService service;

    @GetMapping
    @Operation(summary = "List the agents available to you and how each one runs (chat, generate or job)")
    public List<AgentConnectorService.ConnectableAgent> list() {
        return service.list();
    }

    @PostMapping("/{agentId}/messages")
    @Operation(summary = "Send a message to a chat agent on your own thread and get its full reply")
    public Map<String, String> chat(@PathVariable String agentId, @RequestBody MessageRequest body, HttpServletRequest req) {
        String reply = service.chat(agentId, ConnectorPrincipal.from(req).clientUserId(), body.message());
        return Map.of("agentId", agentId, "reply", reply);
    }

    @PostMapping("/{agentId}/generate")
    @Operation(summary = "Run a generate agent once; the result is saved to your assets")
    public GeneratedDocument generate(@PathVariable String agentId, @RequestBody GenerateRequest body, HttpServletRequest req) {
        return service.generate(agentId, ConnectorPrincipal.from(req).clientUserId(), body.input(), body.title());
    }

    @PostMapping("/{agentId}/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Start regenerating a long-running agent's output; poll the job, then approve or reject the draft")
    public ConnectorJob startJob(@PathVariable String agentId, @RequestBody(required = false) JobRequest body, HttpServletRequest req) {
        return service.startJob(agentId, ConnectorPrincipal.from(req).clientUserId(), body == null ? null : body.feedback());
    }

    @GetMapping("/jobs/{jobId}")
    @Operation(summary = "Poll one of your jobs")
    public ConnectorJob job(@PathVariable String jobId, HttpServletRequest req) {
        return service.findJob(jobId, ConnectorPrincipal.from(req).clientUserId());
    }

    @PostMapping("/jobs/{jobId}/approve")
    @Operation(summary = "Apply a job's draft")
    public ConnectorJob approve(@PathVariable String jobId, HttpServletRequest req) {
        return service.approveJob(jobId, ConnectorPrincipal.from(req).clientUserId());
    }

    @PostMapping("/jobs/{jobId}/reject")
    @Operation(summary = "Discard a job's draft and keep the current output")
    public ConnectorJob reject(@PathVariable String jobId, HttpServletRequest req) {
        return service.rejectJob(jobId, ConnectorPrincipal.from(req).clientUserId());
    }

    public record MessageRequest(String message) {}
    public record GenerateRequest(String input, String title) {}
    public record JobRequest(String feedback) {}
}
