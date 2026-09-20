package ai.genesisbrands.repository;

import ai.genesisbrands.model.WaitlistEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WaitlistEntryRepository extends JpaRepository<WaitlistEntry, String> {
    boolean existsByEmailIgnoreCase(String email);
}
