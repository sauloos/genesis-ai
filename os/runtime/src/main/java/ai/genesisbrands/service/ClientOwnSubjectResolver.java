package ai.genesisbrands.service;

import ai.genesisbrands.model.ClientUser;

import java.util.Optional;

/**
 * Resolves the {@link ConsultantSubject} id that belongs to an already-authenticated
 * {@link ClientUser} — the generic form of {@code ClientConsultantController}'s original
 * session→subject resolution. No default implementation is registered: a tenant that
 * supplies one lets every generic customer-facing agent controller (e.g.
 * {@code GeneratedDocumentController}, {@code ClientAgentChatController}) enrich its
 * prompts with that client's own brand context; a bare platform deployment (or any tenant
 * that hasn't wired up this linkage yet) simply has no enrichment — callers treat an empty
 * result as "proceed without live context", never as an error.
 */
public interface ClientOwnSubjectResolver {

    Optional<String> resolveOwnSubjectId(ClientUser client);
}
