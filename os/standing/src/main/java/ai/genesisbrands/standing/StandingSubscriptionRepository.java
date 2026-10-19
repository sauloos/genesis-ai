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

public interface StandingSubscriptionRepository extends JpaRepository<StandingSubscription, String> {

    Optional<StandingSubscription> findByClientUserIdAndAgentId(String clientUserId, String agentId);

    List<StandingSubscription> findByClientUserId(String clientUserId);

    List<StandingSubscription> findByActiveTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(Instant now, Pageable page);

    List<StandingSubscription> findAllByOrderByUpdatedAtDesc(Pageable page);

    /**
     * Claims a due slot: moves {@code nextRunAt} on only if nobody else has since. A
     * replica that loses the race updates zero rows and skips the slot.
     */
    @Modifying
    @Transactional
    @Query("update StandingSubscription s set s.nextRunAt = ?3, s.lastRunAt = ?4 where s.id = ?1 and s.nextRunAt = ?2 and s.active = true")
    int claim(String id, Instant expectedNextRunAt, Instant newNextRunAt, Instant now);
}
