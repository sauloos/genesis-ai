package ai.genesisbrands.repository;

import ai.genesisbrands.model.FlowSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.time.Instant;
import java.util.Optional;

public interface FlowSessionRepository extends JpaRepository<FlowSession, String> {
    Optional<FlowSession> findByTokenAndExpiresAtAfter(String token, Instant now);

    /** Row-locked read for check-then-write flows (e.g. BrandResultsController's
     *  idempotent pipeline-start) that must not double-fire under concurrent requests. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<FlowSession> findWithLockByTokenAndExpiresAtAfter(String token, Instant now);

    void deleteAllByExpiresAtBefore(Instant cutoff);
}
