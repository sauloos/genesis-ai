package ai.genesisbrands.usage;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface UsageEventRepository extends JpaRepository<UsageEvent, String> {

    List<UsageEvent> findByOccurredAtGreaterThanEqualOrderByOccurredAtDesc(Instant since, Pageable page);

    List<UsageEvent> findByOccurredAtGreaterThanEqual(Instant since);

    @Query("select coalesce(sum(e.costMicros), 0) from UsageEvent e where e.clientUserId = ?1 and e.occurredAt >= ?2")
    long sumCostMicrosForClientSince(String clientUserId, Instant since);

    @Query("select coalesce(sum(e.costMicros), 0) from UsageEvent e where e.runId = ?1")
    long sumCostMicrosForRun(String runId);
}
