package ai.genesisbrands.connector.brain;

import ai.genesisbrands.service.RetrievalService;
import ai.genesisbrands.service.RetrievalService.KnowledgeHit;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * The Brain connector: knowledge retrieval over the reasoning substrate, with no agent
 * reasoning on top. Which layers are searchable is deployment config
 * ({@code genesis.connectors.brain.layers}), never caller-selectable. It defaults to Layer 1
 * and Layer 2. Layer 2 holds other clients' engagement records and is not yet anonymised on
 * ingest — that is a pending item (GDPR anonymisation); until it lands, a deployment can drop
 * Layer 2 by setting the property to {@code layer1}.
 */
@Service
@RequiredArgsConstructor
public class BrainConnectorService {

    static final int MAX_TOP_K = 20;
    static final int DEFAULT_TOP_K = 5;
    private static final int MAX_QUERY_LENGTH = 4000;

    private final RetrievalService retrieval;

    @Value("${genesis.connectors.brain.layers:layer1,layer2}")
    private List<String> layers;

    public List<KnowledgeHit> search(String query, Integer topK) {
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'query' is required");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'query' is longer than " + MAX_QUERY_LENGTH + " characters");
        }
        int k = topK == null ? DEFAULT_TOP_K : Math.max(1, Math.min(MAX_TOP_K, topK));
        try {
            return retrieval.search(query.strip(), k, layers);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Knowledge search is unavailable right now");
        }
    }

    public List<String> layers() {
        return List.copyOf(layers);
    }
}
