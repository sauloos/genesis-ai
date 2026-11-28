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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.regex.Pattern;

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
public class EngagementWorkspaceProvider implements ClientWorkspaceProvider, ClientVariantChooser {

    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");

    private final EngagementRepository engagementRepo;
    private final EngagementDirectionVersionRepository versionRepo;
    private final ObjectMapper objectMapper;

    // Plain-words lines under each direction's name; tenant wording, so they are config.
    @Value("${genesis.brands.directions.descriptor.anchored:Builds on who you are today}")
    private String anchoredDescriptor;
    @Value("${genesis.brands.directions.descriptor.evolved:A confident step forward}")
    private String evolvedDescriptor;
    @Value("${genesis.brands.directions.descriptor.disruptive:The boldest move in your category}")
    private String disruptiveDescriptor;

    @Override
    public List<ClientWorkItem> listMine(String clientUserId) {
        return engagementRepo.findAllByClientUserIdOrderByCreatedAtDesc(clientUserId).stream()
            .map(e -> switch (e.getStatus()) {
                case DONE -> toWorkItem(e);
                case FAILED -> new ClientWorkItem(e.getId(), "Your brand", false, List.of(), false,
                    "Something went wrong creating your brand. Please contact us and we'll sort it out.");
                default -> new ClientWorkItem(e.getId(), "Your brand", false, List.of(), true,
                    "Genesis AI is creating your brand. This usually takes a few minutes.");
            })
            .filter(w -> !w.variants().isEmpty() || w.statusNote() != null)
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

    @Override
    public ClientWorkItem choose(String workItemId, String variantKey, String clientUserId) {
        Engagement e = engagementRepo.findById(workItemId).orElse(null);
        if (e == null || !clientUserId.equals(e.getClientUserId())) {
            throw new NoSuchElementException("Work item not found: " + workItemId);
        }
        if (e.getStatus() != Engagement.Status.DONE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Your brand is not finished yet");
        }
        EngagementResults results = parseResults(e.getResultsJson());
        DirectionOutput chosen = results == null ? null : results.directions().stream()
            .filter(d -> d.direction().equalsIgnoreCase(variantKey)).findFirst().orElse(null);
        if (chosen == null) {
            throw new NoSuchElementException("Unknown direction: " + variantKey);
        }
        e.setChosenDirection(chosen.direction().toUpperCase());
        e.setUpdatedAt(Instant.now());
        return toWorkItem(engagementRepo.save(e));
    }

    private ClientWorkItem toWorkItem(Engagement e) {
        EngagementResults results = parseResults(e.getResultsJson());
        boolean unlocked = e.getPaymentStatus() == Engagement.PaymentStatus.PAID;
        List<DirectionOutput> dirs = results != null ? results.directions() : List.of();
        String title = dirs.isEmpty() || dirs.get(0).brief() == null ? "Your brand"
            : dirs.get(0).brief().brand().name();

        List<ClientWorkItem.Variant> variants = dirs.stream()
            .filter(dir -> dir.pdfBlobPath() != null || dir.logo() != null)
            .map(dir -> toVariant(e.getId(), e.getChosenDirection(), dir, true, true))
            .toList();

        return new ClientWorkItem(e.getId(), title, unlocked, variants, false, null);
    }

    private ClientWorkItem.Variant toVariant(String engagementId, String chosenDirection,
                                              DirectionOutput dir, boolean isCurrent, boolean withDecision) {
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

        ClientWorkItem.Decision decision = withDecision ? decisionOf(engagementId, dir) : null;
        return new ClientWorkItem.Variant(dir.direction(), chosen, assets, versions, decision);
    }

    /** What the chooser shows for one direction: the brief's own words, palette and type. */
    private ClientWorkItem.Decision decisionOf(String engagementId, DirectionOutput dir) {
        var purpose = dir.brief() == null ? null : dir.brief().purpose();
        var visual = dir.visualIdentity();
        List<String> palette = visual == null || visual.colorPalette() == null ? List.of()
            : visual.colorPalette().stream().map(c -> c == null ? null : c.hex())
                .filter(h -> h != null && HEX.matcher(h).matches()).limit(5).toList();
        String font = visual == null || visual.typography() == null ? null : visual.typography().headlineFont();
        String tagline = dir.copy() == null ? null : dir.copy().tagline();
        return new ClientWorkItem.Decision(
            capitalize(dir.direction()),
            descriptorOf(dir.direction()),
            clean(tagline),
            clean(font),
            purpose == null ? null : clean(purpose.summary()),
            purpose == null ? null : clean(purpose.archetype()),
            palette,
            purpose == null ? List.of() : cleanList(purpose.retains()),
            purpose == null ? List.of() : cleanList(purpose.releases()),
            purpose == null ? null : clean(purpose.bet()),
            purpose == null ? null : clean(purpose.risk()),
            "/api/engagements/" + engagementId + "/preview/" + dir.direction());
    }

    private String descriptorOf(String direction) {
        return switch (direction.toUpperCase()) {
            case "ANCHORED" -> anchoredDescriptor;
            case "EVOLVED" -> evolvedDescriptor;
            case "DISRUPTIVE" -> disruptiveDescriptor;
            default -> null;
        };
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static List<String> cleanList(List<String> items) {
        return items == null ? List.of()
            : items.stream().map(EngagementWorkspaceProvider::clean).filter(java.util.Objects::nonNull).limit(3).toList();
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
            return Optional.of(toVariant(version.getEngagementId(), null, dir, false, false));
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
