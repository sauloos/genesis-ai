package ai.genesisbrands.platform;

/**
 * Extension point: a tenant registers a specialist agent so Playground can discover it
 * generically. This is a registry entry, not an invocation contract — each agent's real
 * request/response shape stays whatever the tenant's own controller defines; testEndpoint()
 * just tells the caller where to POST a test run.
 */
public interface CoreAgent {

    String agentId();

    String displayName();

    String description();

    String testEndpoint();
}
