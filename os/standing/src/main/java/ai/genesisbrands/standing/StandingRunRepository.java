package ai.genesisbrands.standing;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StandingRunRepository extends JpaRepository<StandingRun, String> {

    List<StandingRun> findByStartedAtGreaterThanEqualOrderByStartedAtDesc(Instant since, Pageable page);

    List<StandingRun> findByClientUserIdOrderByStartedAtDesc(String clientUserId, Pageable page);

    long countBySubscriptionIdAndStatusAndStartedAtGreaterThanEqual(String subscriptionId, String status, Instant since);

    long countBySubscriptionIdAndStatusInAndStartedAtGreaterThanEqual(String subscriptionId, Collection<String> statuses, Instant since);

    boolean existsBySubscriptionIdAndStatus(String subscriptionId, String status);

    boolean existsBySubscriptionIdAndTriggerAndStatusNotAndStartedAtAfter(String subscriptionId, String trigger, String status, Instant after);

    Optional<StandingRun> findFirstBySubscriptionIdAndStatusAndStartedAtLessThanOrderByStartedAtDesc(
        String subscriptionId, String status, Instant before);

    /** Runs left RUNNING by a restart; the scheduler fails them after a grace period. */
    List<StandingRun> findByStatusAndStartedAtLessThan(String status, Instant before);
}
