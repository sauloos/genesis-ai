package ai.genesisbrands.connector.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConnectorKeyRepository extends JpaRepository<ConnectorKey, String> {
    Optional<ConnectorKey> findByKeyHash(String keyHash);
    List<ConnectorKey> findAllByOrderByCreatedAtDesc();
    List<ConnectorKey> findAllByClientUserIdOrderByCreatedAtDesc(String clientUserId);
}
