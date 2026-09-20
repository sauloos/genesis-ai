package ai.genesisbrands.agent.core;

public record EvaluationResult(
    Verdict verdict,
    String summary,
    AgentRevision revision // null when verdict is ACCEPT
) {
    public enum Verdict { ACCEPT, REVISE }
}
