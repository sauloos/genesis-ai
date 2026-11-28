package ai.genesisbrands.repository;

import ai.genesisbrands.model.Engagement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EngagementRepository extends JpaRepository<Engagement, String> {
    List<Engagement> findAllByOrderByCreatedAtDesc();
    List<Engagement> findAllBySourceOrderByCreatedAtDesc(Engagement.Source source);
    List<Engagement> findAllByClientUserIdOrderByCreatedAtDesc(String clientUserId);
    Optional<Engagement> findFirstByStatusOrderByCreatedAtDesc(Engagement.Status status);
    List<Engagement> findAllByStatusOrderByCreatedAtDesc(Engagement.Status status);

    @Query("select e.id from Engagement e where e.status in :active and e.updatedAt < :cutoff")
    List<String> findStaleRunIds(@Param("active") Collection<Engagement.Status> active, @Param("cutoff") Instant cutoff);

    /**
     * Fails one engagement only if it is still in an active status and untouched since the cutoff,
     * in one statement, so a run that finishes between the lookup and this call is never overwritten.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Engagement e set e.status = :failed, e.errorMessage = :message, e.updatedAt = :now "
        + "where e.id = :id and e.status in :active and e.updatedAt < :cutoff")
    int failIfStale(@Param("id") String id, @Param("failed") Engagement.Status failed,
                    @Param("active") Collection<Engagement.Status> active, @Param("message") String message,
                    @Param("now") Instant now, @Param("cutoff") Instant cutoff);
}
