package ai.genesisbrands.connector.mcp;

import ai.genesisbrands.connector.agent.AgentConnectorService;
import ai.genesisbrands.connector.agent.AgentConnectorService.ConnectableAgent;
import ai.genesisbrands.connector.auth.ConnectorPrincipal;
import ai.genesisbrands.connector.brain.BrainConnectorService;
import ai.genesisbrands.connector.platform.PlatformConnectorService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static ai.genesisbrands.connector.mcp.McpTool.*;

/**
 * Builds the MCP tool list for each connector from the same services the REST faces use.
 * Agent tools are derived per call from the live agent catalog, so a newly adopted (and
 * connector-enabled) agent shows up as tools with no code here.
 */
@Component
@RequiredArgsConstructor
public class McpToolsets {

    private final AgentConnectorService agents;
    private final BrainConnectorService brain;
    private final PlatformConnectorService platform;
    private final ObjectMapper objectMapper;
    private final List<ClientToolContributor> contributors;

    /**
     * Agent connector tools. {@code onlyAgentId} non-null scopes the server to one agent (the
     * per-agent connector, e.g. the Logo connector); null offers every agent available to the caller.
     */
    public List<McpTool> agentTools(ConnectorPrincipal principal, String onlyAgentId) {
        List<ConnectableAgent> available = agents.list().stream()
            .filter(a -> onlyAgentId == null || a.agentId().equals(onlyAgentId))
            .toList();
        if (onlyAgentId != null && available.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not available: " + onlyAgentId);
        }
        String client = principal.clientUserId();
        List<McpTool> tools = new ArrayList<>();
        boolean anyJobs = false;
        for (ConnectableAgent agent : available) {
            String prefix = agent.agentId().replace('-', '_');
            String about = agent.displayName() + (agent.description() == null ? "" : " — " + agent.description());
            switch (agent.mode()) {
                case CHAT -> tools.add(new McpTool(prefix + "_chat",
                    "Send a message to " + about + ". Continues your own ongoing conversation and returns the full reply.",
                    schema(Map.of("message", string("What to say to the agent")), "message"),
                    args -> Map.of("reply", agents.chat(agent.agentId(), client, text(args, "message")))));
                case GENERATE -> tools.add(new McpTool(prefix + "_generate",
                    "Run " + about + " once. The result is returned and saved to your assets.",
                    schema(Map.of(
                        "input", string("The material or request to work on"),
                        "title", string("Optional title for the saved asset")), "input"),
                    args -> agents.generate(agent.agentId(), client, text(args, "input"), optionalText(args, "title"))));
                case JOB -> {
                    anyJobs = true;
                    tools.add(new McpTool(prefix + "_regenerate",
                        "Start regenerating your " + agent.displayName() + " output. Runs for several minutes: poll get_job, "
                            + "then approve_job to apply the draft or reject_job to keep the current version.",
                        schema(Map.of("feedback", string("Optional direction for the new version"))),
                        args -> agents.startJob(agent.agentId(), client, optionalText(args, "feedback"))));
                }
            }
        }
        if (anyJobs) {
            Map<String, Object> jobSchema = schema(Map.of("jobId", string("The job id returned when the job was started")), "jobId");
            tools.add(new McpTool("get_job", "Check a regeneration job's status and, once ready, its draft.",
                jobSchema, args -> agents.findJob(text(args, "jobId"), client)));
            tools.add(new McpTool("approve_job", "Apply a finished job's draft, replacing the current version.",
                jobSchema, args -> agents.approveJob(text(args, "jobId"), client)));
            tools.add(new McpTool("reject_job", "Discard a finished job's draft and keep the current version.",
                jobSchema, args -> agents.rejectJob(text(args, "jobId"), client)));
        }
        if (onlyAgentId == null) contributors.forEach(c -> tools.addAll(c.tools(client)));
        return tools;
    }

    public List<McpTool> brainTools() {
        return List.of(new McpTool("search_knowledge",
            "Search the knowledge base by meaning. Returns the closest passages with their titles, sources and scores.",
            schema(Map.of(
                "query", string("What to look for, in plain language"),
                "topK", integer("How many passages to return (1-20, default 5)")), "query"),
            args -> Map.of("layers", brain.layers(),
                "results", brain.search(text(args, "query"), args.hasNonNull("topK") ? args.get("topK").asInt() : null))));
    }

    public List<McpTool> platformTools(ConnectorPrincipal principal) {
        return List.of(
            new McpTool("list_operations",
                "List the admin operations you can run (everything the admin dashboard can do), optionally filtered by a search term.",
                schema(Map.of("query", string("Optional search term, e.g. 'page-flow' or 'theme'"))),
                args -> platform.list(optionalText(args, "query"))),
            new McpTool("describe_operation",
                "Show one admin operation's method, path, summary and the parameters it takes.",
                schema(Map.of("operationId", string("An id from list_operations")), "operationId"),
                args -> platform.describe(text(args, "operationId"))),
            new McpTool("call_operation",
                "Run one admin operation. Every call is audited against your key. The result carries the operation's own HTTP status and body.",
                schema(Map.of(
                    "operationId", string("An id from list_operations"),
                    "pathParams", object("Values for the operation's path parameters, by name"),
                    "query", object("Values for the operation's query parameters, by name"),
                    "body", object("JSON request body, for operations that take one")), "operationId"),
                args -> platform.call(text(args, "operationId"), new PlatformConnectorService.CallRequest(
                    args.hasNonNull("pathParams") ? objectMapper.convertValue(args.get("pathParams"), STRING_MAP) : null,
                    args.hasNonNull("query") ? objectMapper.convertValue(args.get("query"), OBJECT_MAP) : null,
                    args.get("body")), principal)));
    }

    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, String>> STRING_MAP = new com.fasterxml.jackson.core.type.TypeReference<>() {};
    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> OBJECT_MAP = new com.fasterxml.jackson.core.type.TypeReference<>() {};

    public static String text(JsonNode args, String field) {
        String value = optionalText(args, field);
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'" + field + "' is required");
        }
        return value;
    }

    public static String optionalText(JsonNode args, String field) {
        JsonNode node = args.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }
}
