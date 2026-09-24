package ai.genesisbrands.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A visitor's in-progress traversal of a PageFlow's PageTransition graph. DB-backed
 * opaque token, mirrors ClientSession's shape (not AdminSessionService's stateless
 * cookie) since real traversal state must persist across requests.
 */
@Entity
@Table(name = "flow_sessions")
@Data
@NoArgsConstructor
public class FlowSession {

    @Id
    @Column(length = 36)
    private String token;

    @Column(name = "page_flow_id", nullable = false, length = 36)
    private String pageFlowId;

    @Column(name = "current_page_id", length = 36)
    private String currentPageId;

    @Column(nullable = false)
    private boolean ended = false;

    @Column(name = "context_json", columnDefinition = "TEXT")
    private String contextJson;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
