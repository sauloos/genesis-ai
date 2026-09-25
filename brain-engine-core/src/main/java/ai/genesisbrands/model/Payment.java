package ai.genesisbrands.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "payments")
@Data
@NoArgsConstructor
public class Payment {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "flow_session_token", nullable = false)
    private String flowSessionToken;

    @Column(name = "widget_id", nullable = false)
    private String widgetId;

    @Column(name = "product_id", nullable = false, length = 36)
    private String productId;

    @Column(name = "product_option_id", nullable = false, length = 36)
    private String productOptionId;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Mode mode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(name = "stripe_checkout_session_id")
    private String stripeCheckoutSessionId;

    @Column(name = "stripe_payment_intent_id")
    private String stripePaymentIntentId;

    @Column(name = "client_user_id")
    private String clientUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public enum Mode { MOCK, TEST, LIVE }

    public enum Status { PENDING, SUCCEEDED, FAILED, CANCELED }
}
