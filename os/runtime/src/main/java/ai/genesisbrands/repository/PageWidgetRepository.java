package ai.genesisbrands.repository;

import ai.genesisbrands.model.PageWidget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PageWidgetRepository extends JpaRepository<PageWidget, String> {
    List<PageWidget> findByPageIdOrderByOrderInSlotAsc(String pageId);
    List<PageWidget> findByPageIdInOrderByOrderInSlotAsc(List<String> pageIds);
    void deleteByPageId(String pageId);
}
