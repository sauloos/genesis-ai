package ai.genesisbrands.model;

import ai.genesisbrands.platform.PageFlowRouting;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A named, versioned container of Pages bound to a URL slug. Exactly one PageFlow is
 * "live" per resolved route (rootPrefix + slug) at a time (see PageFlowService.setLive)
 * — mirrors Theme's active-flag pattern, scoped to route instead of global.
 */
@Entity
@Table(name = "page_flows")
@Data
@NoArgsConstructor
public class PageFlow {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 255)
    private String slug;

    /** Null = default ("live"). "" = explicitly root-mounted, no prefix. Any other
     *  value = that literal prefix segment. See PageFlowRouting. */
    @Column(name = "root_prefix")
    private String rootPrefix;

    @Column(nullable = false)
    private boolean live = false;

    @Column(name = "start_page_id", length = 36)
    private String startPageId;

    /** "END_PAGE" (display endPageId, a Page flagged isEndPage) or "REDIRECT_FLOW" (start endTargetFlowId). Null = End not configured. */
    @Column(name = "end_action", length = 32)
    private String endAction;

    @Column(name = "end_page_id", length = 36)
    private String endPageId;

    @Column(name = "end_target_flow_id", length = 36)
    private String endTargetFlowId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /** The resolved live URL path — always correct, wherever a PageFlow is serialized. */
    @Transient
    public String getLivePath() {
        return PageFlowRouting.fullPath(rootPrefix, slug);
    }
}
