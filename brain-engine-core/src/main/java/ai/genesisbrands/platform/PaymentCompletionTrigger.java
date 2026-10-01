package ai.genesisbrands.platform;

/**
 * Extension point: brain-engine-core has no compile-time dependency on the Engagement
 * pipeline (it lives in the tenant app, e.g. genesis-brands), so PaymentService resolves
 * this interface via Spring DI instead of calling it directly. Mirrors
 * FlowEngagementTrigger's core-defines/tenant-fills-in pattern.
 */
public interface PaymentCompletionTrigger {

    /** Called once a Payment transitions to SUCCEEDED — mock instant-success, a Stripe
     *  webhook, or a poll-time status refresh all funnel through the same call site.
     *  flowSessionToken is the session whose cart was just paid for; clientUserId
     *  (nullable) is the ClientUser it was linked to, if any. */
    void onPaymentSucceeded(String flowSessionToken, String clientUserId);
}
