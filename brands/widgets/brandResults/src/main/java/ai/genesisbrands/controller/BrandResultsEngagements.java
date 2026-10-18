package ai.genesisbrands.controller;

import java.util.Optional;

/**
 * What the brandResults start handshake needs to know about engagements. The adopting app
 * implements it over its own Engagement model, so this module never depends on that model.
 */
public interface BrandResultsEngagements {

    /** An engagement's pipeline status (PENDING, RUNNING, DONE or FAILED), and whether its
     *  results hold at least one direction. */
    record State(String status, boolean hasDirections) {

        /** Failed, or done without a single direction (e.g. brief derivation failed
         *  silently) — either way the visitor is stuck and may start again. */
        public boolean retryable() {
            return "FAILED".equals(status) || ("DONE".equals(status) && !hasDirections);
        }
    }

    Optional<State> find(String engagementId);

    /**
     * Mock mode: copies the newest finished engagement that has directions into a new row
     * owned by this client, so owner-gated actions (choose-direction, preview) work as usual.
     * Empty when no such engagement exists.
     */
    Optional<String> cloneLatestFinishedFor(String clientUserId);
}
