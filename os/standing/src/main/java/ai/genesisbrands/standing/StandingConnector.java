package ai.genesisbrands.standing;

import ai.genesisbrands.connector.auth.ConnectorPrincipal;
import ai.genesisbrands.connector.mcp.ClientToolContributor;
import ai.genesisbrands.connector.mcp.McpTool;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static ai.genesisbrands.connector.mcp.McpTool.integer;
import static ai.genesisbrands.connector.mcp.McpTool.object;
import static ai.genesisbrands.connector.mcp.McpTool.schema;
import static ai.genesisbrands.connector.mcp.McpTool.string;
import static ai.genesisbrands.connector.mcp.McpToolsets.optionalText;
import static ai.genesisbrands.connector.mcp.McpToolsets.text;

/**
 * Standing agents over the connectors gateway, when the app adopts it: REST under
 * {@code /api/connect/standing} and tools on the all-agents MCP server. Both act only for the
 * client the connector key belongs to. Subscribing and changing options stay in the app.
 */
public final class StandingConnector {

    private StandingConnector() {}

    @RestController
    @RequestMapping("/api/connect/standing")
    @ConditionalOnClass(name = "ai.genesisbrands.connector.mcp.ClientToolContributor")
    @RequiredArgsConstructor
    @Tag(name = "Standing agents connector")
    public static class Rest {

        private final StandingService standing;

        @GetMapping("/subscriptions")
        @Operation(summary = "Your standing agents and their schedules")
        public List<StandingService.ClientOffer> subscriptions(HttpServletRequest req) {
            return standing.offersFor(client(req));
        }

        @PostMapping("/subscriptions/{agentId}/run-now")
        @ResponseStatus(HttpStatus.ACCEPTED)
        public StandingService.RunView runNow(@PathVariable String agentId, HttpServletRequest req) {
            return standing.runNow(client(req), agentId);
        }

        @PostMapping("/subscriptions/{agentId}/events")
        @ResponseStatus(HttpStatus.ACCEPTED)
        @Operation(summary = "Send an event-triggered agent one item, e.g. a lead from your CRM or an RFP from your inbox")
        public StandingService.RunView event(@PathVariable String agentId,
                                             @RequestBody StandingClientController.EventRequest body, HttpServletRequest req) {
            return standing.submitEvent(client(req), agentId, body == null ? null : body.fields(), "connector");
        }

        @GetMapping("/inbox")
        public List<StandingService.InboxView> inbox(@RequestParam(defaultValue = "PENDING") String status,
                                                     @RequestParam(defaultValue = "20") int limit, HttpServletRequest req) {
            return standing.inboxFor(client(req), status, limit);
        }

        @PostMapping("/inbox/{itemId}/approve")
        public StandingService.InboxView approve(@PathVariable String itemId,
                                                 @RequestBody(required = false) StandingClientController.Decision body,
                                                 HttpServletRequest req) {
            return standing.decide(client(req), itemId, true, body == null ? null : body.feedback());
        }

        @PostMapping("/inbox/{itemId}/reject")
        public StandingService.InboxView reject(@PathVariable String itemId,
                                                @RequestBody(required = false) StandingClientController.Decision body,
                                                HttpServletRequest req) {
            return standing.decide(client(req), itemId, false, body == null ? null : body.feedback());
        }

        private static String client(HttpServletRequest req) {
            return ConnectorPrincipal.from(req).clientUserId();
        }
    }

    @Component
    @ConditionalOnClass(name = "ai.genesisbrands.connector.mcp.ClientToolContributor")
    @RequiredArgsConstructor
    public static class Tools implements ClientToolContributor {

        private final StandingService standing;

        @Override
        public List<McpTool> tools(String client) {
            Map<String, Object> itemSchema = schema(Map.of(
                "itemId", string("The inbox item's id"),
                "feedback", string("Optional note; the agent reads it next time")), "itemId");
            return List.of(
                new McpTool("standing_list_subscriptions",
                    "List the standing agents offered to you, with your schedule and options for each one you subscribe to.",
                    schema(Map.of()), args -> standing.offersFor(client)),
                new McpTool("standing_run_now",
                    "Run one of your standing agents now instead of waiting for its schedule. Its output arrives in your inbox.",
                    schema(Map.of("agentId", string("The agent's id, from standing_list_subscriptions")), "agentId"),
                    args -> standing.runNow(client, text(args, "agentId"))),
                new McpTool("standing_send_event",
                    "Send one item to a standing agent that runs on events, such as a new lead for Lead to proposal. "
                        + "The fields it takes are its eventFields in standing_list_subscriptions. Its output arrives in your inbox.",
                    schema(Map.of(
                        "agentId", string("The agent's id, from standing_list_subscriptions"),
                        "fields", object("The event's fields by key, each a string")), "agentId", "fields"),
                    args -> standing.submitEvent(client, text(args, "agentId"), fields(args), "connector")),
                new McpTool("standing_list_inbox",
                    "List what your standing agents delivered, newest first.",
                    schema(Map.of(
                        "status", string("PENDING (default), APPROVED, REJECTED or ALL"),
                        "limit", integer("How many items (1-100, default 20)"))),
                    args -> standing.inboxFor(client,
                        optionalText(args, "status") == null ? "PENDING" : optionalText(args, "status"),
                        args.hasNonNull("limit") ? args.get("limit").asInt() : 20)),
                new McpTool("standing_approve_item", "Approve an item in your inbox.", itemSchema,
                    args -> standing.decide(client, text(args, "itemId"), true, optionalText(args, "feedback"))),
                new McpTool("standing_reject_item", "Reject an item in your inbox.", itemSchema,
                    args -> standing.decide(client, text(args, "itemId"), false, optionalText(args, "feedback"))));
        }

        private static Map<String, String> fields(com.fasterxml.jackson.databind.JsonNode args) {
            Map<String, String> out = new java.util.LinkedHashMap<>();
            args.path("fields").properties().forEach(e -> {
                if (!e.getValue().isNull()) out.put(e.getKey(), e.getValue().isTextual() ? e.getValue().asText() : e.getValue().toString());
            });
            return out;
        }
    }
}
