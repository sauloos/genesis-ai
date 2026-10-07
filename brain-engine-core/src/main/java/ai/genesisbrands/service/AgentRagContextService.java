package ai.genesisbrands.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Shared RAG context builder for every generic agent (CREATE or chat-based) built on
 * {@link GenericSingleShotAgentService} / {@link GenericAgentChatService} — extracted
 * from {@code ConsultantService}'s existing context-building logic rather than
 * re-derived per agent. {@link ConsultantService} itself is untouched; it keeps its own
 * richer subject/tool model.
 * <p>
 * Context is always additive: Layer 1 + Layer 2 retrieval apply unconditionally, and a
 * "Current Client Context" block is appended only when a live subject id is supplied and
 * a tenant has registered a {@link ConsultantSubjectProvider}. Playground never passes a
 * live subject id (no owning client); a customer-facing caller with no resolvable subject
 * simply gets the generic-RAG-only block.
 */
@Service
@RequiredArgsConstructor
public class AgentRagContextService {

    private static final int RETRIEVAL_TOP_K = 5;

    private final Layer1Service layer1;
    private final RetrievalService retrieval;
    private final ObjectProvider<ConsultantSubjectProvider> subjectProvider;

    public String buildContext(String query, String liveSubjectId) {
        StringBuilder sb = new StringBuilder();

        String layer1Context = layer1.buildContextBlock();
        if (!layer1Context.isBlank()) {
            sb.append(layer1Context).append("\n\n");
        }

        String retrieved = retrieval.retrieve(query, RETRIEVAL_TOP_K);
        if (!retrieved.isBlank()) {
            sb.append("# Relevant Knowledge\n\n").append(retrieved).append("\n\n");
        }

        if (liveSubjectId != null) {
            appendLiveSubjectBlock(sb, liveSubjectId);
        }

        return sb.toString();
    }

    private void appendLiveSubjectBlock(StringBuilder sb, String liveSubjectId) {
        ConsultantSubjectProvider provider = subjectProvider.getIfAvailable();
        if (provider == null) return;
        ConsultantSubject subject;
        try {
            subject = provider.find(liveSubjectId);
        } catch (Exception e) {
            return; // unresolvable subject — proceed without live context, never fail the request
        }
        if (subject == null) return;

        sb.append("# Current Client Context\n\n");
        if (subject.name() != null && !subject.name().isBlank()) {
            sb.append("**Name:** ").append(subject.name()).append("\n");
        }
        if (subject.industry() != null && !subject.industry().isBlank()) {
            sb.append("**Industry:** ").append(subject.industry()).append("\n");
        }
        if (subject.audience() != null && !subject.audience().isBlank()) {
            sb.append("**Audience:** ").append(subject.audience()).append("\n");
        }
        if (subject.brief() != null && !subject.brief().isBlank()) {
            sb.append("\n**Brief:**\n").append(subject.brief());
        }
        sb.append("\n\n");
    }
}
