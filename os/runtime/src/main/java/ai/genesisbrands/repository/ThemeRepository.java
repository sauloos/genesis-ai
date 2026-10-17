package ai.genesisbrands.repository;

import ai.genesisbrands.model.Theme;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ThemeRepository extends JpaRepository<Theme, String> {

    Optional<Theme> findByActiveTrue();

    @Modifying
    @Query("UPDATE Theme t SET t.active = false")
    void deactivateAll();
}
