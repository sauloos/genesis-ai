package ai.genesisbrands.publishing;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PublishJobRepository extends JpaRepository<PublishJob, String> {

    List<PublishJob> findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(String status, Instant now, Pageable page);

    List<PublishJob> findByStatusAndStartedAtLessThan(String status, Instant before);

    List<PublishJob> findByClientUserIdAndSourceRefOrderByCreatedAtAsc(String clientUserId, String sourceRef);

    List<PublishJob> findByClientUserIdOrderByScheduledAtDesc(String clientUserId, Pageable page);

    List<PublishJob> findAllByOrderByCreatedAtDesc(Pageable page);

    Optional<PublishJob> findByIdAndClientUserId(String id, String clientUserId);

    boolean existsByClientUserIdAndSourceRefAndAccountIdAndStatusIn(String clientUserId, String sourceRef, String accountId,
                                                                    Collection<String> statuses);

    List<PublishJob> findByAccountIdAndStatus(String accountId, String status);

    long countByStatus(String status);

    /**
     * Claims a due job: only one replica's update matches, the others update zero rows and
     * skip it, so a post is never published twice by two schedulers.
     */
    @Modifying
    @Transactional
    @Query("update PublishJob j set j.status = 'RUNNING', j.startedAt = ?2, j.attempts = j.attempts + 1 where j.id = ?1 and j.status = 'QUEUED'")
    int claim(String id, Instant now);
}
