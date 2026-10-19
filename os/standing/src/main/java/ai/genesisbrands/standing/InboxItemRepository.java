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

public interface InboxItemRepository extends JpaRepository<InboxItem, String> {

    List<InboxItem> findByClientUserIdOrderByCreatedAtDesc(String clientUserId, Pageable page);

    List<InboxItem> findByClientUserIdAndStatusOrderByCreatedAtDesc(String clientUserId, String status, Pageable page);

    List<InboxItem> findByClientUserIdAndAgentIdAndStatusInOrderByDecidedAtDesc(
        String clientUserId, String agentId, Collection<String> statuses, Pageable page);

    long countByClientUserIdAndStatus(String clientUserId, String status);

    Optional<InboxItem> findByIdAndClientUserId(String id, String clientUserId);
}
