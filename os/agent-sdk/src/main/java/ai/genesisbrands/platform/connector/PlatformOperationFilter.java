package ai.genesisbrands.platform.connector;

/**
 * Extension point: narrows which collected admin operations the Platform connector offers.
 * Operations are collected by convention from every {@code /api/**} handler, so a new admin
 * page is a new Platform connector capability with no central list; filters only take things
 * away. An operation is offered only when every registered filter includes it — core ships a
 * default that drops customer-facing, auth and connector routes, and a tenant adds its own
 * bean to drop more.
 */
public interface PlatformOperationFilter {

    boolean include(PlatformOperation operation);
}
