package ai.genesisbrands.service;

import java.time.Instant;

/**
 * One entry in a subject's conversation list (see {@link ConsultantService#listConversations}).
 * {@code title} is derived from the conversation's first user message, truncated — there is
 * no separate user-authored title.
 */
public record ConversationSummary(String conversationId, String title, Instant startedAt, Instant lastMessageAt) {
}
