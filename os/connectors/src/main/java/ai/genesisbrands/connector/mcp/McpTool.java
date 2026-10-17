package ai.genesisbrands.connector.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.function.Function;

/**
 * One MCP tool: its advertised shape and the call that runs it. {@code handler} receives the
 * call's {@code arguments} (never null — an empty object when none were sent) and returns any
 * JSON-serialisable result.
 */
public record McpTool(String name, String description, Map<String, Object> inputSchema, Function<JsonNode, Object> handler) {

    /** A JSON Schema object with the given properties; {@code required} lists the mandatory ones. */
    public static Map<String, Object> schema(Map<String, Object> properties, String... required) {
        return required.length == 0
            ? Map.of("type", "object", "properties", properties)
            : Map.of("type", "object", "properties", properties, "required", java.util.List.of(required));
    }

    public static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    public static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    public static Map<String, Object> object(String description) {
        return Map.of("type", "object", "description", description);
    }
}
