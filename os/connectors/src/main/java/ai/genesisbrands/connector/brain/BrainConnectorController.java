package ai.genesisbrands.connector.brain;

import ai.genesisbrands.service.RetrievalService.KnowledgeHit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** REST face of the Brain connector. The MCP face ({@code /mcp/brain}) calls the same service. */
@RestController
@RequestMapping("/api/connect/brain")
@RequiredArgsConstructor
@Tag(name = "Brain Connector", description = "Knowledge retrieval — authenticate with a client connector key")
public class BrainConnectorController {

    private final BrainConnectorService service;

    @PostMapping("/search")
    @Operation(summary = "Search the knowledge base by meaning; returns the closest passages with their sources")
    public SearchResponse search(@RequestBody SearchRequest body) {
        return new SearchResponse(service.layers(), service.search(body.query(), body.topK()));
    }

    public record SearchRequest(String query, Integer topK) {}
    public record SearchResponse(List<String> layers, List<KnowledgeHit> results) {}
}
