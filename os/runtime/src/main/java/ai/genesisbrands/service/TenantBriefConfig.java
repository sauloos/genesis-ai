package ai.genesisbrands.service;

/**
 * Extension point: each tenant provides their own system prompt for brief derivation.
 * The platform's BriefDerivationService owns the structure (two-phase reasoning,
 * JSON parsing, ChatClient wiring); the tenant owns the creative persona and
 * direction definitions.
 */
public interface TenantBriefConfig {
    String systemPrompt();
}
