package ai.genesisbrands.service;

import ai.genesisbrands.model.Payment;
import ai.genesisbrands.model.Product;
import ai.genesisbrands.model.ProductOption;
import ai.genesisbrands.repository.PaymentRepository;
import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Charges for a previously made ProductOptions selection via Stripe Checkout — or, when
 * genesis.payments.mode=MOCK (the default), simulates a successful payment without ever
 * contacting Stripe. Mirrors the genesis.flow.mock-brand-results pattern so the payment
 * widget can be exercised end-to-end in the live page flow at zero cost and without a
 * Stripe account. TEST and LIVE both go through real Stripe Checkout; which of the two
 * configured secret keys is used is driven by this same mode property, so switching
 * from test to live traffic is a single property flip rather than a key swap.
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepo;
    private final ProductService productService;

    @Value("${genesis.payments.mode:MOCK}")
    private String modeProperty;

    @Value("${stripe.test-secret-key:}")
    private String testSecretKey;

    @Value("${stripe.live-secret-key:}")
    private String liveSecretKey;

    @Value("${stripe.test-webhook-secret:}")
    private String testWebhookSecret;

    @Value("${stripe.live-webhook-secret:}")
    private String liveWebhookSecret;

    public Payment.Mode currentMode() {
        try {
            return Payment.Mode.valueOf(modeProperty.trim().toUpperCase());
        } catch (Exception e) {
            return Payment.Mode.MOCK;
        }
    }

    public CheckoutOutcome startCheckout(String token, String widgetId, String clientUserId,
                                          String productId, String optionId,
                                          String successUrl, String cancelUrl) {
        Product product = productService.getProduct(productId);
        ProductOption option = productService.getOption(productId, optionId);
        if (!product.isActive() || !option.isActive()) {
            throw new IllegalStateException("This option is not currently available");
        }

        Payment payment = new Payment();
        payment.setId(UUID.randomUUID().toString());
        payment.setFlowSessionToken(token);
        payment.setWidgetId(widgetId);
        payment.setProductId(productId);
        payment.setProductOptionId(optionId);
        payment.setAmountCents(option.getPriceCents());
        payment.setCurrency(option.getCurrency());
        payment.setClientUserId(clientUserId);

        Payment.Mode mode = currentMode();
        payment.setMode(mode);

        if (mode == Payment.Mode.MOCK) {
            payment.setStatus(Payment.Status.SUCCEEDED);
            paymentRepo.save(payment);
            return new CheckoutOutcome(payment, null);
        }

        String secretKey = secretKeyFor(mode);
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("Stripe " + mode + " secret key is not configured");
        }
        Stripe.apiKey = secretKey;

        boolean recurring = option.getBillingInterval() != null && !option.getBillingInterval().isBlank();
        SessionCreateParams.LineItem.PriceData.Builder priceData = SessionCreateParams.LineItem.PriceData.builder()
            .setCurrency(option.getCurrency())
            .setUnitAmount(option.getPriceCents())
            .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                .setName(product.getName() + " — " + option.getName())
                .build());
        if (recurring) {
            priceData.setRecurring(SessionCreateParams.LineItem.PriceData.Recurring.builder()
                .setInterval(mapInterval(option.getBillingInterval()))
                .build());
        }

        SessionCreateParams params = SessionCreateParams.builder()
            .setMode(recurring ? SessionCreateParams.Mode.SUBSCRIPTION : SessionCreateParams.Mode.PAYMENT)
            .setSuccessUrl(successUrl)
            .setCancelUrl(cancelUrl)
            .putMetadata("paymentId", payment.getId())
            .addLineItem(SessionCreateParams.LineItem.builder()
                .setQuantity(1L)
                .setPriceData(priceData.build())
                .build())
            .build();

        try {
            Session session = Session.create(params);
            payment.setStripeCheckoutSessionId(session.getId());
            paymentRepo.save(payment);
            return new CheckoutOutcome(payment, session.getUrl());
        } catch (StripeException e) {
            payment.setStatus(Payment.Status.FAILED);
            paymentRepo.save(payment);
            throw new IllegalStateException("Stripe checkout could not be started: " + e.getMessage(), e);
        }
    }

    public Payment status(String token, String widgetId) {
        Payment payment = paymentRepo.findFirstByFlowSessionTokenAndWidgetIdOrderByCreatedAtDesc(token, widgetId)
            .orElse(null);
        if (payment != null && payment.getStatus() == Payment.Status.PENDING
                && payment.getMode() != Payment.Mode.MOCK && payment.getStripeCheckoutSessionId() != null) {
            refreshFromStripe(payment);
        }
        return payment;
    }

    /** Verifies the signature against the given mode's configured webhook secret before
     *  trusting the payload — called by the mode-specific webhook route (Stripe test-mode
     *  and live-mode events are delivered to separate endpoints, each with its own secret,
     *  so the mode never has to be guessed from the payload). */
    public void handleWebhook(Payment.Mode mode, String payload, String signatureHeader) {
        String webhookSecret = mode == Payment.Mode.LIVE ? liveWebhookSecret : testWebhookSecret;
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalStateException("Stripe " + mode + " webhook secret is not configured");
        }
        Event event;
        try {
            event = Webhook.constructEvent(payload, signatureHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            throw new IllegalArgumentException("Invalid Stripe webhook signature", e);
        }
        if (!"checkout.session.completed".equals(event.getType()) && !"checkout.session.expired".equals(event.getType())) {
            return;
        }
        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
        deserializer.getObject().filter(o -> o instanceof Session).map(o -> (Session) o).ifPresent(session ->
            paymentRepo.findByStripeCheckoutSessionId(session.getId()).ifPresent(payment -> {
                if ("checkout.session.expired".equals(event.getType())) {
                    payment.setStatus(Payment.Status.CANCELED);
                    paymentRepo.save(payment);
                } else {
                    applySessionStatus(payment, session);
                }
            }));
    }

    private void refreshFromStripe(Payment payment) {
        String secretKey = secretKeyFor(payment.getMode());
        if (secretKey == null || secretKey.isBlank()) return;
        Stripe.apiKey = secretKey;
        try {
            Session session = Session.retrieve(payment.getStripeCheckoutSessionId());
            applySessionStatus(payment, session);
        } catch (StripeException e) {
            log.warn("Could not refresh payment {} from Stripe: {}", payment.getId(), e.getMessage());
        }
    }

    private void applySessionStatus(Payment payment, Session session) {
        if (payment.getStatus() != Payment.Status.PENDING) return;
        if ("paid".equals(session.getPaymentStatus()) || "complete".equals(session.getStatus())) {
            payment.setStatus(Payment.Status.SUCCEEDED);
            payment.setStripePaymentIntentId(session.getPaymentIntent());
            paymentRepo.save(payment);
        } else if ("expired".equals(session.getStatus())) {
            payment.setStatus(Payment.Status.CANCELED);
            paymentRepo.save(payment);
        }
    }

    private String secretKeyFor(Payment.Mode mode) {
        return mode == Payment.Mode.LIVE ? liveSecretKey : testSecretKey;
    }

    private SessionCreateParams.LineItem.PriceData.Recurring.Interval mapInterval(String billingInterval) {
        return switch (billingInterval.toLowerCase()) {
            case "year", "yearly", "annual" -> SessionCreateParams.LineItem.PriceData.Recurring.Interval.YEAR;
            case "week", "weekly" -> SessionCreateParams.LineItem.PriceData.Recurring.Interval.WEEK;
            case "day", "daily" -> SessionCreateParams.LineItem.PriceData.Recurring.Interval.DAY;
            default -> SessionCreateParams.LineItem.PriceData.Recurring.Interval.MONTH;
        };
    }

    public record CheckoutOutcome(Payment payment, String checkoutUrl) {}
}
