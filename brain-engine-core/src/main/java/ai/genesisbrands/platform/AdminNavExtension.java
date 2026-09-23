package ai.genesisbrands.platform;

/**
 * Extension point: a tenant contributes an extra admin nav item (and the page it points
 * to lives in the tenant's own module). The shared admin shell (console.html, dashboard.html,
 * etc.) discovers these via GET /api/admin/nav-extensions instead of hardcoding them, so a
 * bare platform instance with no tenant beans registered shows none.
 */
public interface AdminNavExtension {

    String label();

    String path();

    default String description() {
        return "Extension registered by this tenant.";
    }

    default int order() {
        return 100;
    }
}
