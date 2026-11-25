package ai.genesisbrands.repository;

import ai.genesisbrands.model.KnowledgeCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KnowledgeCaseRepository extends JpaRepository<KnowledgeCase, String> {

    List<KnowledgeCase> findAllByOrderByCreatedAtDesc();

    List<KnowledgeCase> findByKindAndStatusOrderByCreatedAtDesc(String kind, String status);
}
