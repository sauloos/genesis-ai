package ai.genesisbrands.platform.standing;

/**
 * Adds a section to the client context every standing run receives. The runtime ships the
 * generic sections (the client's own subject/brand, their deliverables, recent inbox
 * activity); a tenant or module registers a bean to add what only it knows. Called per run,
 * server-side, with the run's client identity only.
 */
public interface StandingContextContributor {

    /** Markdown for this client, or null/blank when there is nothing to add. */
    String section(String clientUserId, String agentId);

    default int order() {
        return 100;
    }
}
