package ai.genesisbrands.repository;

import ai.genesisbrands.model.FlowSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface FlowSessionRepository extends JpaRepository<FlowSession, String> {
    Optional<FlowSession> findByTokenAndExpiresAtAfter(String token, Instant now);
    void deleteAllByExpiresAtBefore(Instant cutoff);
}
