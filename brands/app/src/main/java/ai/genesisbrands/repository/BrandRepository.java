package ai.genesisbrands.repository;

import ai.genesisbrands.model.Brand;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrandRepository extends JpaRepository<Brand, String> {
}
