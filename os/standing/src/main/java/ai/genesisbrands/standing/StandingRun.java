package ai.genesisbrands.standing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One execution of a subscription. {@code (subscriptionId, slot)} is unique: a scheduled slot
 * runs at most once even if two replicas both see it due.
 */
@Entity
@Table(name = "standing_runs",
    uniqueConstraints = @UniqueConstraint(name = "uk_standing_run_slot", columnNames = {"subscription_id", "slot"}),
    indexes = {
        @Index(name = "idx_standing_run_started", columnList = "started_at"),
        @Index(name = "idx_standing_run_client", columnList = "client_user_id, started_at")
    })
@Data
@NoArgsConstructor
public class StandingRun {

    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    /** Not started: the subscription was paused at run time (budget, run cap, offer withdrawn). */
    public static final String SKIPPED = "SKIPPED";

    public static final String TRIGGER_SCHEDULE = "SCHEDULE";
    public static final String TRIGGER_MANUAL = "MANUAL";
    public static final String TRIGGER_EVENT = "EVENT";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "subscription_id", nullable = false, length = 36)
    private String subscriptionId;

    @Column(name = "client_user_id", nullable = false, length = 36)
    private String clientUserId;

    @Column(name = "agent_id", nullable = false, length = 80)
    private String agentId;

    /** The scheduled time this run fills (or the request time, for a manual run). */
    @Column(nullable = false)
    private Instant slot;

    @Column(name = "trigger_kind", nullable = false, length = 20)
    private String trigger;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "items_created", nullable = false)
    private int itemsCreated;

    @Column(name = "cost_micros", nullable = false)
    private long costMicros;

    @Column(length = 500)
    private String note;

    @Column(columnDefinition = "TEXT")
    private String error;

    /** For an EVENT run, the {@code StandingRunContext.Event} it was started with, as JSON. */
    @Column(name = "event_json", columnDefinition = "TEXT")
    private String eventJson;
}
