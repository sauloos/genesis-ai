package ai.genesisbrands.service;

import java.util.List;
import java.util.Optional;

/**
 * Extension point behind the core {@code dashboardAssets}/{@code dashboardAgents} widgets.
 * Mirrors {@link ConsultantSubjectProvider}: the platform owns the mechanics (the widgets,
 * the REST endpoint, auth resolution), the tenant owns what a client's work items are and
 * how to look them up — for Genesis Brands today, a client's PAID/DONE Engagements and their
 * DirectionOutputs.
 */
public interface ClientWorkspaceProvider {

    List<ClientWorkItem> listMine(String clientUserId);

    Optional<ClientWorkItem.Variant> loadVersion(
        String workItemId, String variantKey, int versionNumber, String clientUserId);
}
