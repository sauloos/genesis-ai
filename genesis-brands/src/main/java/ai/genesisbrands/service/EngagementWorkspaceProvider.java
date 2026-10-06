package ai.genesisbrands.service;

import ai.genesisbrands.agent.logo.LogoOutput;
import ai.genesisbrands.model.Engagement;
import ai.genesisbrands.model.EngagementDirectionVersion;
import ai.genesisbrands.repository.EngagementDirectionVersionRepository;
import ai.genesisbrands.repository.EngagementRepository;
import ai.genesisbrands.service.EngagementOrchestratorService.DirectionOutput;
import ai.genesisbrands.service.EngagementOrchestratorService.EngagementResults;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Genesis Brands' answer to "what is a client's work item": a PAID/DONE Engagement, and
 * "what's inside it": its DirectionOutputs (Copy/Visual Identity/Logo/Playbook/Brand Book).
 * Implements the core {@link ClientWorkspaceProvider} SPI so the core
 * {@code dashboardAssets}/{@code dashboardAgents} widgets never call a genesis-brands
 * REST endpoint directly for this listing — only for the byte-serving mechanics
 * (PDF render, logo zip, preview image) that stay genuinely tenant-specific.
 */
@Component
@RequiredArgsConstructor
public class EngagementWorkspaceProvider implements ClientWorkspaceProvider {

    private final EngagementRepository engagementRepo;
    private final EngagementDirectionVersionRepository versionRepo;
    private final ObjectMapper objectMapper;

    @Override
    public List<ClientWorkItem> listMine(String clientUserId) {
        return engagementRepo.findAllByClientUserIdOrderByCreatedAtDesc(clientUserId).stream()
            .filter(e -> e.getStatus() == Engagement.Status.DONE)
            .map(this::toWorkItem)
            .filter(w -> !w.variants().isEmpty())
            .toList();
    }

    @Override
    public Optional<ClientWorkItem.Variant> loadVersion(
        String workItemId, String variantKey, int versionNumber, String clientUserId) {
        Engagement e = engagementRepo.findById(workItemId).orElse(null);
        if (e == null || !clientUserId.equals(e.getClientUserId())) {
            return Optional.empty();
        }
        return versionRepo.findByEngagementIdAndDirectionAndVersionNumber(
                workItemId, variantKey.toUpperCase(), versionNumber)
            .flatMap(this::parseArchivedVariant);
    }

    private ClientWorkItem toWorkItem(Engagement e) {
        EngagementResults results = parseResults(e.getResultsJson());
        boolean unlocked = e.getPaymentStatus() == Engagement.PaymentStatus.PAID;
        List<DirectionOutput> dirs = results != null ? results.directions() : List.of();
        String title = dirs.isEmpty() || dirs.get(0).brief() == null ? "Your brand"
            : dirs.get(0).brief().brand().name();

        List<ClientWorkItem.Variant> variants = dirs.stream()
            .filter(dir -> dir.pdfBlobPath() != null || dir.logo() != null)
            .map(dir -> toVariant(e.getId(), e.getChosenDirection(), dir, true))
            .toList();

        return new ClientWorkItem(e.getId(), title, unlocked, variants);
    }

    private ClientWorkItem.Variant toVariant(String engagementId, String chosenDirection,
                                              DirectionOutput dir, boolean isCurrent) {
        boolean chosen = dir.direction().equalsIgnoreCase(chosenDirection);
        List<ClientWorkItem.Asset> assets = new java.util.ArrayList<>();

        ClientWorkItem.Asset pdf = buildPdfAsset(engagementId, dir, isCurrent);
        if (pdf != null) assets.add(pdf);
        ClientWorkItem.Asset logo = buildLogoAsset(engagementId, dir, isCurrent);
        if (logo != null) assets.add(logo);

        List<ClientWorkItem.VersionRef> versions = isCurrent
            ? versionRepo.findByEngagementIdAndDirectionOrderByVersionNumberDesc(engagementId, dir.direction())
                .stream()
                .map(v -> new ClientWorkItem.VersionRef(v.getVersionNumber(), v.getCreatedAt()))
                .toList()
            : List.of();

        return new ClientWorkItem.Variant(dir.direction(), chosen, assets, versions);
    }

    private ClientWorkItem.Asset buildPdfAsset(String engagementId, DirectionOutput dir, boolean isCurrent) {
        boolean ready = dir.pdfBlobPath() != null;
        if (!ready) return null;
        String label = capitalize(dir.direction()) + " Brand Book";
        String downloadUrl = isCurrent ? "/api/engagements/" + engagementId + "/pdf/" + dir.direction() : null;
        String previewUrl = isCurrent ? "/api/engagements/" + engagementId + "/preview/" + dir.direction() : null;
        return new ClientWorkItem.Asset("document", label, true, previewUrl, downloadUrl, null);
    }

    private ClientWorkItem.Asset buildLogoAsset(String engagementId, DirectionOutput dir, boolean isCurrent) {
        LogoOutput logo = dir.logo();
        if (logo == null) return null;
        String label = capitalize(dir.direction()) + " Logo";
        boolean hasZip = dir.logoZipBlobPath() != null;
        String downloadUrl = (isCurrent && hasZip) ? "/api/engagements/" + engagementId + "/logos/" + dir.direction() : null;
        String previewUrl = isCurrent && logo.imageUrl() != null ? logo.imageUrl() : null;
        String inlineSvg = isCurrent && logo.method() == LogoOutput.Method.SVG_CONCEPT ? logo.svgMarkup() : null;
        return new ClientWorkItem.Asset("image", label, true, previewUrl, downloadUrl, inlineSvg);
    }

    private Optional<ClientWorkItem.Variant> parseArchivedVariant(EngagementDirectionVersion version) {
        try {
            DirectionOutput dir = objectMapper.readValue(version.getDirectionOutputJson(), DirectionOutput.class);
            return Optional.of(toVariant(version.getEngagementId(), null, dir, false));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private EngagementResults parseResults(String resultsJson) {
        if (resultsJson == null) return null;
        try {
            return objectMapper.readValue(resultsJson, EngagementResults.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String capitalize(String direction) {
        return direction.charAt(0) + direction.substring(1).toLowerCase();
    }
}
