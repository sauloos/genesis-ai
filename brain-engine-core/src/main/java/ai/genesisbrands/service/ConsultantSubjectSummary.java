package ai.genesisbrands.service;

import java.time.Instant;

/**
 * Lightweight listing shape for a {@link ConsultantSubject} — used for the conversation
 * sidebar, which only needs identity and recency, not the full prompt-building context.
 */
public record ConsultantSubjectSummary(String id, String name, Instant updatedAt) {
}
