package ai.genesisbrands.repository;

import ai.genesisbrands.model.ProductOption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductOptionRepository extends JpaRepository<ProductOption, String> {
    List<ProductOption> findByProductIdOrderBySortOrderAsc(String productId);
    void deleteByProductId(String productId);
}
