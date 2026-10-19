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

/** A client's configuration of one standing agent: when it runs and with which options. */
@Entity
@Table(name = "standing_subscriptions",
    uniqueConstraints = @UniqueConstraint(name = "uk_standing_sub_client_agent", columnNames = {"client_user_id", "agent_id"}),
    indexes = @Index(name = "idx_standing_sub_due", columnList = "active, next_run_at"))
@Data
@NoArgsConstructor
public class StandingSubscription {

    // A slot over the month's budget or run cap is skipped, not paused, so the subscription
    // picks up again by itself next month. Pausing is always someone's decision.
    public static final String PAUSED_BY_CLIENT = "CLIENT";
    public static final String PAUSED_BY_ADMIN = "ADMIN";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "client_user_id", nullable = false, length = 36)
    private String clientUserId;

    @Column(name = "agent_id", nullable = false, length = 80)
    private String agentId;

    @Column(nullable = false)
    private boolean active;

    /** A {@code Cadence} name — a plain string, so a new cadence never needs a column fixer. */
    @Column(nullable = false, length = 20)
    private String cadence;

    /** 1 (Monday) to 7 (Sunday), for weekly and fortnightly cadences. */
    @Column(name = "day_of_week")
    private Integer dayOfWeek;

    /** 1 to 31, clamped to the month's length, for the monthly cadence. */
    @Column(name = "day_of_month")
    private Integer dayOfMonth;

    /** {@code HH:mm} in {@link #timezone}. */
    @Column(name = "time_of_day", nullable = false, length = 5)
    private String timeOfDay;

    @Column(nullable = false, length = 60)
    private String timezone;

    /** JSON object of the client's own option values. */
    @Column(name = "options_json", columnDefinition = "TEXT")
    private String optionsJson;

    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    /** Why an inactive subscription stopped: one of the {@code PAUSED_*} constants. */
    @Column(name = "paused_reason", length = 20)
    private String pausedReason;

    /** Email the client when a run leaves something in the inbox; null (rows from before the
     *  setting existed) means yes. */
    @Column(name = "notify_by_email")
    private Boolean notifyByEmail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt;
}
