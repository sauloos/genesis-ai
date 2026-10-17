package ai.genesisbrands.connector.platform;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlatformAuditRepository extends JpaRepository<PlatformAuditEntry, String> {
    List<PlatformAuditEntry> findAllByOrderByCreatedAtDesc(Pageable page);
}
