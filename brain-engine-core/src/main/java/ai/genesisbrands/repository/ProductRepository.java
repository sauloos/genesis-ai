package ai.genesisbrands.repository;

import ai.genesisbrands.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, String> {
    List<Product> findAllByOrderBySortOrderAsc();
    List<Product> findByActiveTrueOrderBySortOrderAsc();
}
