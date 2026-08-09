package ai.genesisbrands.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "engagements")
@Data
@NoArgsConstructor
public class Engagement {

    @Id
    @Column(length = 36)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source;

    @Column(name = "questionnaire_response_id")
    private String questionnaireResponseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "eval_mode", nullable = false)
    private boolean evalMode;

    /** JSON array of 3 DirectionBrief objects derived by the Brain Engine. */
    @Column(name = "briefs_json", columnDefinition = "TEXT")
    private String briefsJson;

    /** JSON EngagementResults — all 5 agent outputs for all 3 directions. */
    @Column(name = "results_json", columnDefinition = "TEXT")
    private String resultsJson;

    @Column(name = "client_email")
    private String clientEmail;

    @Column(name = "client_name")
    private String clientName;

    @Column(name = "client_user_id", length = 36)
    private String clientUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false)
    private PaymentStatus paymentStatus = PaymentStatus.NONE;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public enum Source { SIMULATION, CLIENT }
    public enum Status { PENDING, RUNNING, DONE, FAILED }
    public enum PaymentStatus { NONE, REQUESTED, PAID }
}
