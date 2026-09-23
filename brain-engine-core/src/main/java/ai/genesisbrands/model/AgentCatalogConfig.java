package ai.genesisbrands.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Admin-set per-agent config, keyed by {@code agentId}. A row exists only once an
 * admin has explicitly changed a setting — absence means "use the defaults" (see
 * AgentCatalogService), not "not yet added".
 */
@Entity
@Table(name = "agent_catalog_configs")
@Data
@NoArgsConstructor
public class AgentCatalogConfig {

    @Id
    @Column(length = 64)
    private String agentId;

    @Column(name = "available_for_live_view", nullable = false)
    private boolean availableForLiveView = true;

    @Column(name = "available_for_playground", nullable = false)
    private boolean availableForPlayground = true;

    @Column(name = "ab_compare_enabled", nullable = false)
    private boolean abCompareEnabled = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
