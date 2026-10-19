package ai.genesisbrands.security;

/**
 * A module's /api/** route that ApiKeyFilter lets through without the shared X-Api-Key —
 * typically one loaded by a plain {@code <img src>}, which can't attach a header. The route
 * must enforce its own access check. Lets a tenant exempt its own routes without core naming
 * them.
 */
public interface ApiKeyExemption {

    boolean exempts(String path);
}
