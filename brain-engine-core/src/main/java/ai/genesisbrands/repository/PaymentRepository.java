package ai.genesisbrands.repository;

import ai.genesisbrands.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, String> {

    Optional<Payment> findFirstByFlowSessionTokenAndWidgetIdOrderByCreatedAtDesc(String flowSessionToken, String widgetId);

    Optional<Payment> findByStripeCheckoutSessionId(String stripeCheckoutSessionId);
}
