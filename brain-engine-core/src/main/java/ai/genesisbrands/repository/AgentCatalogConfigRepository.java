package ai.genesisbrands.repository;

import ai.genesisbrands.model.AgentCatalogConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentCatalogConfigRepository extends JpaRepository<AgentCatalogConfig, String> {
}
