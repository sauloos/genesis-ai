package ai.genesisbrands.service;

import ai.genesisbrands.platform.standing.KnowledgeSearch;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** The runtime's {@link KnowledgeSearch}: the same vector search the agents' RAG uses. */
@Component
@RequiredArgsConstructor
public class RetrievalKnowledgeSearch implements KnowledgeSearch {

    private final RetrievalService retrieval;

    @Override
    public List<Passage> search(String query, int topK, List<String> layers) {
        if (query == null || query.isBlank()) return List.of();
        return retrieval.search(query.strip(), Math.max(1, Math.min(20, topK)), layers).stream()
            .map(h -> new Passage(h.title(), h.text(), h.sourceUrl(), h.layer(), h.score()))
            .toList();
    }
}
