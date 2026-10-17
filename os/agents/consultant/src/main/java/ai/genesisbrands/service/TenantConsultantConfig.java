package ai.genesisbrands.service;

import java.util.List;

/**
 * Extension point: each tenant supplies its own consultant persona — system prompt,
 * scope guardrails, and the redirect message for out-of-scope questions. The platform's
 * ConsultantService owns the mechanics (retrieval, history, streaming); the tenant owns
 * the voice and boundaries.
 */
public interface TenantConsultantConfig {

    String systemPrompt();

    default List<String> outOfScopeKeywords() {
        return List.of();
    }

    default String outOfScopeResponse() {
        return "That's outside what I can help with here.";
    }

    default boolean isOutOfScope(String message) {
        String lower = message.toLowerCase();
        return outOfScopeKeywords().stream().anyMatch(lower::contains);
    }
}
