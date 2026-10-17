package ai.genesisbrands.repository;

import ai.genesisbrands.model.Page;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PageRepository extends JpaRepository<Page, String> {
    List<Page> findByPageFlowId(String pageFlowId);
    void deleteByPageFlowId(String pageFlowId);
}
