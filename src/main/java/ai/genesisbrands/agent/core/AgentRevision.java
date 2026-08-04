package ai.genesisbrands.agent.core;

import java.util.List;

public record AgentRevision(
    String feedback,
    List<String> specificIssues,
    List<String> fieldsToRevise
) {}
