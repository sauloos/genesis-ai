package ai.genesisbrands.service;

import ai.genesisbrands.model.Engagement;
import ai.genesisbrands.model.FlowSession;
import ai.genesisbrands.platform.PaymentCompletionTrigger;
import ai.genesisbrands.repository.EngagementRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Fills the brain-engine-core PaymentCompletionTrigger extension point: when a Payment
 * succeeds (mock or real Stripe), finds whichever Engagement(s) the same FlowSession
 * created — stored under the "__engagement:<widgetId>" context keys BrandResultsController
 * writes — and flips their paymentStatus to PAID, the same effect playground.html's
 * admin-only /mark-paid button has always had for test data. Without this, no live
 * client-facing payment (mocked or real) ever unlocked an engagement's downloads.
 */
@Component
@RequiredArgsConstructor
public class EngagementPaymentCompletionTrigger implements PaymentCompletionTrigger {

    private static final Logger log = LoggerFactory.getLogger(EngagementPaymentCompletionTrigger.class);
    private static final String ENGAGEMENT_CONTEXT_PREFIX = "__engagement:";

    private final FlowSessionService flowSessionService;
    private final EngagementRepository engagementRepo;
    private final ObjectMapper objectMapper;

    @Override
    public void onPaymentSucceeded(String flowSessionToken, String clientUserId) {
        FlowSession session;
        try {
            session = flowSessionService.get(flowSessionToken);
        } catch (NoSuchElementException e) {
            log.warn("Payment succeeded for expired/missing FlowSession {} — no engagement to mark paid", flowSessionToken);
            return;
        }
        parseContext(session.getContextJson()).forEach((key, value) -> {
            if (key.startsWith(ENGAGEMENT_CONTEXT_PREFIX)) {
                markPaid(String.valueOf(value));
            }
        });
    }

    private void markPaid(String engagementId) {
        engagementRepo.findById(engagementId).ifPresent(e -> {
            if (e.getPaymentStatus() == Engagement.PaymentStatus.PAID) return;
            e.setPaymentStatus(Engagement.PaymentStatus.PAID);
            e.setPaidAt(Instant.now());
            e.setUpdatedAt(Instant.now());
            engagementRepo.save(e);
            log.info("Engagement {} marked PAID after successful payment", engagementId);
        });
    }

    private Map<String, Object> parseContext(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
