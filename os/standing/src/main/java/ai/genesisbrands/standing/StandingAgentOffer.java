package ai.genesisbrands.standing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * The admin side of a standing agent: whether clients are offered it and within which limits.
 * No row means "not offered" — a newly adopted standing agent is inert until an admin enables
 * it. Applies to every client of the tenant until product entitlements land.
 */
@Entity
@Table(name = "standing_agent_offers")
@Data
@NoArgsConstructor
public class StandingAgentOffer {

    @Id
    @Column(name = "agent_id", length = 80)
    private String agentId;

    @Column(nullable = false)
    private boolean enabled;

    /** JSON object of option key → value, overriding the agent's own defaults. */
    @Column(name = "default_options_json", columnDefinition = "TEXT")
    private String defaultOptionsJson;

    /** Comma-separated option keys the client may not change (their default applies). */
    @Column(name = "locked_options", length = 1000)
    private String lockedOptions;

    /** Comma-separated cadences, narrowing the agent's own set; null means the agent's set. */
    @Column(name = "allowed_cadences", length = 200)
    private String allowedCadences;

    /** Runs per client per calendar month (UTC); null means no cap. */
    @Column(name = "max_runs_per_month")
    private Integer maxRunsPerMonth;

    /** Model spend per client per calendar month (UTC) across all usage, in micro-dollars;
     *  null means no cap. A run that would start over it pauses the subscription. */
    @Column(name = "monthly_budget_micros")
    private Long monthlyBudgetMicros;

    /** Comma-separated product ids; when set, only a client who has paid for one of them is
     *  offered the agent (see {@link StandingEntitlements}). Null means every client. */
    @Column(name = "required_products", length = 1000)
    private String requiredProducts;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
