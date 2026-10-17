package ai.genesisbrands.repository;

import ai.genesisbrands.model.ClientSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface ClientSessionRepository extends JpaRepository<ClientSession, String> {
    Optional<ClientSession> findByTokenAndExpiresAtAfter(String token, Instant now);
    void deleteAllByClientUserId(String clientUserId);
    void deleteAllByExpiresAtBefore(Instant now);
}
