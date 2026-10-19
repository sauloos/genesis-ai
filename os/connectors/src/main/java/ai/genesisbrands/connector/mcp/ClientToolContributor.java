package ai.genesisbrands.connector.mcp;

import java.util.List;

/**
 * Extension point: a module adds its own client-scoped tools to the all-agents MCP server
 * ({@code /mcp/agents}) without editing {@link McpToolsets}. Tools act only for
 * {@code clientUserId}, which the connector key resolved server-side.
 */
public interface ClientToolContributor {

    List<McpTool> tools(String clientUserId);
}
