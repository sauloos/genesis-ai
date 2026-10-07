package ai.genesisbrands.repository;

import ai.genesisbrands.model.GeneratedDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GeneratedDocumentRepository extends JpaRepository<GeneratedDocument, String> {

    List<GeneratedDocument> findAllByAgentIdAndClientUserIdOrderByCreatedAtDesc(String agentId, String clientUserId);

    // Playground-generated documents have a null clientUserId.
    List<GeneratedDocument> findAllByAgentIdAndClientUserIdIsNullOrderByCreatedAtDesc(String agentId);
}
