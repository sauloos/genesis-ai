package ai.genesisbrands.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A named, versioned container of Pages bound to a URL slug. Exactly one PageFlow is
 * "live" per slug at a time (see PageFlowRepository.deactivateAllForSlug) — mirrors
 * Theme's active-flag pattern, scoped to slug instead of global.
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

    @Column(nullable = false)
    private boolean live = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
