package ai.genesisbrands.service;

import ai.genesisbrands.model.Payment;
import ai.genesisbrands.platform.PaymentCompletionTrigger;
import ai.genesisbrands.repository.PaymentRepository;
import ai.genesisbrands.service.CartService.CartItem;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Charges for a FlowSession's whole cart (one or more CartService.CartItem selections) in
 * a single Stripe Checkout — or, when genesis.payments.mode=MOCK (the default), simulates
 * a successful payment without ever contacting Stripe. Mirrors the
 * genesis.flow.mock-brand-results pattern so the payment widget can be exercised
 * end-to-end in the live page flow at zero cost and without a Stripe account. TEST and
 * LIVE both go through real Stripe Checkout; which of the two configured secret keys is
 * used is driven by this same mode property, so switching from test to live traffic is a
 * single property flip rather than a key swap. A cart with any recurring item uses
 * SUBSCRIPTION mode (Stripe allows one-time price_data line items alongside recurring
 * ones there); an all-one-time cart uses PAYMENT mode.
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepo;
    private final ObjectMapper objectMapper;
    private final Optional<PaymentCompletionTrigger> paymentCompletionTrigger;

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
                                          List<CartItem> cart, String successUrl, String cancelUrl) {
        if (cart.isEmpty()) {
            throw new IllegalStateException("Your cart is empty");
        }
        String currency = cart.get(0).currency();
        boolean mixedCurrency = cart.stream().anyMatch(i -> !currency.equalsIgnoreCase(i.currency()));
        if (mixedCurrency) {
            throw new IllegalStateException("Cart items must share a single currency");
        }
        long totalCents = cart.stream().mapToLong(CartItem::priceCents).sum();
        boolean recurring = cart.stream().anyMatch(i -> i.billingInterval() != null && !i.billingInterval().isBlank());

        Payment payment = new Payment();
        payment.setId(UUID.randomUUID().toString());
        payment.setFlowSessionToken(token);
        payment.setWidgetId(widgetId);
        payment.setCartJson(writeCart(cart));
        payment.setAmountCents(totalCents);
        payment.setCurrency(currency);
        payment.setClientUserId(clientUserId);

        Payment.Mode mode = currentMode();
        payment.setMode(mode);

        if (mode == Payment.Mode.MOCK) {
            payment.setStatus(Payment.Status.SUCCEEDED);
            paymentRepo.save(payment);
            notifyPaymentSucceeded(payment);
            return new CheckoutOutcome(payment, null);
        }

        String secretKey = secretKeyFor(mode);
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("Stripe " + mode + " secret key is not configured");
        }
        Stripe.apiKey = secretKey;

        SessionCreateParams.Builder paramsBuilder = SessionCreateParams.builder()
            .setMode(recurring ? SessionCreateParams.Mode.SUBSCRIPTION : SessionCreateParams.Mode.PAYMENT)
            .setSuccessUrl(successUrl)
            .setCancelUrl(cancelUrl)
            .putMetadata("paymentId", payment.getId());

        for (CartItem item : cart) {
            boolean itemRecurring = item.billingInterval() != null && !item.billingInterval().isBlank();
            SessionCreateParams.LineItem.PriceData.Builder priceData = SessionCreateParams.LineItem.PriceData.builder()
                .setCurrency(item.currency())
                .setUnitAmount(item.priceCents())
                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                    .setName(item.productName() + " — " + item.optionName())
                    .build());
            if (itemRecurring) {
                priceData.setRecurring(SessionCreateParams.LineItem.PriceData.Recurring.builder()
                    .setInterval(mapInterval(item.billingInterval()))
                    .build());
            }
            paramsBuilder.addLineItem(SessionCreateParams.LineItem.builder()
                .setQuantity(1L)
                .setPriceData(priceData.build())
                .build());
        }

        SessionCreateParams params = paramsBuilder.build();

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
            notifyPaymentSucceeded(payment);
        } else if ("expired".equals(session.getStatus())) {
            payment.setStatus(Payment.Status.CANCELED);
            paymentRepo.save(payment);
        }
    }

    private void notifyPaymentSucceeded(Payment payment) {
        paymentCompletionTrigger.ifPresent(t ->
            t.onPaymentSucceeded(payment.getFlowSessionToken(), payment.getClientUserId()));
    }

    private String secretKeyFor(Payment.Mode mode) {
        return mode == Payment.Mode.LIVE ? liveSecretKey : testSecretKey;
    }

    private String writeCart(List<CartItem> cart) {
        try {
            return objectMapper.writeValueAsString(cart);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize cart snapshot", e);
        }
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
