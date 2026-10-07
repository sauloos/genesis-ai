package ai.genesisbrands.service;

import ai.genesisbrands.model.GeneratedDocument;
import ai.genesisbrands.repository.GeneratedDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GeneratedDocumentService {

    private final GeneratedDocumentRepository repository;

    public List<GeneratedDocument> listFor(String agentId, String clientUserId) {
        return clientUserId == null
            ? repository.findAllByAgentIdAndClientUserIdIsNullOrderByCreatedAtDesc(agentId)
            : repository.findAllByAgentIdAndClientUserIdOrderByCreatedAtDesc(agentId, clientUserId);
    }

    public GeneratedDocument save(String agentId, String clientUserId, String title, String contentMarkdown) {
        GeneratedDocument doc = new GeneratedDocument();
        doc.setId(UUID.randomUUID().toString());
        doc.setAgentId(agentId);
        doc.setClientUserId(clientUserId);
        doc.setTitle(title);
        doc.setContentMarkdown(contentMarkdown);
        doc.setCreatedAt(Instant.now());
        return repository.save(doc);
    }
}
