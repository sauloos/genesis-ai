package ai.genesisbrands.connector.mcp;

import ai.genesisbrands.connector.auth.ConnectorPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The MCP face of the connectors — a stateless Streamable HTTP server (JSON-RPC 2.0 over POST,
 * plain JSON responses, no server-initiated stream, so GET is 405). Four servers, one per
 * connector:
 * <ul>
 *   <li>{@code /mcp/agents} — every agent available to the caller;</li>
 *   <li>{@code /mcp/agents/{agentId}} — one agent's own connector (e.g. the Logo connector);</li>
 *   <li>{@code /mcp/brain} — the Brain connector;</li>
 *   <li>{@code /mcp/platform} — the Platform connector (platform key only).</li>
 * </ul>
 * Authentication is {@link ai.genesisbrands.connector.auth.ConnectorAuthFilter}'s, before any of this runs.
 */
@Slf4j
@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
public class McpController {

    static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");

    private final McpToolsets toolsets;
    private final ObjectMapper objectMapper;

    @PostMapping(value = "/agents", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> agents(@RequestBody JsonNode message, HttpServletRequest req) {
        ConnectorPrincipal principal = ConnectorPrincipal.from(req);
        return handle(message, "genesis-agents", "Agents available to your account.", () -> toolsets.agentTools(principal, null));
    }

    @PostMapping(value = "/agents/{agentId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> agent(@PathVariable String agentId, @RequestBody JsonNode message, HttpServletRequest req) {
        ConnectorPrincipal principal = ConnectorPrincipal.from(req);
        // Resolve once up front so a disabled or unknown agent's connector is a 404, not an empty server.
        toolsets.agentTools(principal, agentId);
        return handle(message, "genesis-agent-" + agentId, "The " + agentId + " agent.", () -> toolsets.agentTools(principal, agentId));
    }

    @PostMapping(value = "/brain", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> brain(@RequestBody JsonNode message) {
        return handle(message, "genesis-brain", "Knowledge retrieval over the brain.", toolsets::brainTools);
    }

    @PostMapping(value = "/platform", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> platform(@RequestBody JsonNode message, HttpServletRequest req) {
        ConnectorPrincipal principal = ConnectorPrincipal.from(req);
        return handle(message, "genesis-platform",
            "Admin operations: discover with list_operations, inspect with describe_operation, run with call_operation.",
            () -> toolsets.platformTools(principal));
    }

    /** No server-to-client stream is offered; per the Streamable HTTP transport, GET is then 405. */
    @GetMapping({"", "/**"})
    public ResponseEntity<Void> noStream() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header("Allow", "POST").build();
    }

    private ResponseEntity<JsonNode> handle(JsonNode message, String serverName, String instructions, Supplier<List<McpTool>> tools) {
        if (message.isArray()) {
            ArrayNode responses = objectMapper.createArrayNode();
            message.forEach(m -> dispatch(m, serverName, instructions, tools).ifPresent(responses::add));
            return responses.isEmpty() ? ResponseEntity.accepted().build() : ResponseEntity.ok(responses);
        }
        return dispatch(message, serverName, instructions, tools)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.accepted().build());
    }

    /** One JSON-RPC message → its response, or empty for a notification / client response. */
    private Optional<JsonNode> dispatch(JsonNode msg, String serverName, String instructions, Supplier<List<McpTool>> tools) {
        if (!msg.isObject() || !msg.hasNonNull("method")) {
            // A response from the client (we never send requests) or garbage without an id.
            return msg.has("id") && !msg.has("result") && !msg.has("error")
                ? Optional.of(error(msg.get("id"), -32600, "Invalid request"))
                : Optional.empty();
        }
        String method = msg.get("method").asText();
        JsonNode id = msg.get("id");
        if (id == null || id.isNull()) return Optional.empty(); // notification: nothing to answer
        JsonNode params = msg.hasNonNull("params") ? msg.get("params") : objectMapper.createObjectNode();

        return Optional.of(switch (method) {
            case "initialize" -> result(id, initialize(params, serverName, instructions));
            case "ping" -> result(id, objectMapper.createObjectNode());
            case "tools/list" -> result(id, listTools(tools.get()));
            case "tools/call" -> callTool(id, params, tools.get());
            default -> error(id, -32601, "Method not found: " + method);
        });
    }

    private ObjectNode initialize(JsonNode params, String serverName, String instructions) {
        String requested = params.path("protocolVersion").asText("");
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", SUPPORTED_PROTOCOL_VERSIONS.contains(requested) ? requested : SUPPORTED_PROTOCOL_VERSIONS.get(0));
        result.putObject("capabilities").putObject("tools").put("listChanged", false);
        result.putObject("serverInfo").put("name", serverName).put("version", "1.0.0");
        result.put("instructions", instructions);
        return result;
    }

    private ObjectNode listTools(List<McpTool> tools) {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode list = result.putArray("tools");
        for (McpTool tool : tools) {
            ObjectNode t = list.addObject();
            t.put("name", tool.name());
            t.put("description", tool.description());
            t.set("inputSchema", objectMapper.valueToTree(tool.inputSchema()));
        }
        return result;
    }

    private JsonNode callTool(JsonNode id, JsonNode params, List<McpTool> tools) {
        String name = params.path("name").asText("");
        Optional<McpTool> tool = tools.stream().filter(t -> t.name().equals(name)).findFirst();
        if (tool.isEmpty()) {
            return error(id, -32602, "Unknown tool: " + name);
        }
        JsonNode args = params.path("arguments").isObject() ? params.get("arguments") : objectMapper.createObjectNode();
        try {
            Object output = tool.get().handler().apply(args);
            return result(id, toolResult(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output), false));
        } catch (ResponseStatusException e) {
            return result(id, toolResult(e.getReason() != null ? e.getReason() : e.getStatusCode().toString(), true));
        } catch (IllegalArgumentException | java.util.NoSuchElementException e) {
            return result(id, toolResult(e.getMessage(), true));
        } catch (Exception e) {
            log.warn("MCP tool {} failed", name, e);
            return result(id, toolResult("The tool failed. Try again, or check the input.", true));
        }
    }

    private ObjectNode toolResult(String text, boolean isError) {
        ObjectNode result = objectMapper.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", text == null ? "" : text);
        result.put("isError", isError);
        return result;
    }

    private ObjectNode result(JsonNode id, JsonNode result) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        return response;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.putObject("error").put("code", code).put("message", message);
        return response;
    }
}
