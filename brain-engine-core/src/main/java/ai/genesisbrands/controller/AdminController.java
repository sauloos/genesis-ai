package ai.genesisbrands.controller;

import ai.genesisbrands.platform.AdminNavExtension;
import ai.genesisbrands.platform.ModuleOrigin;
import ai.genesisbrands.security.AdminAuthHelper;
import ai.genesisbrands.security.AdminSessionService;
import ai.genesisbrands.service.ConsultantSubjectProvider;
import ai.genesisbrands.service.ThemeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminAuthHelper adminAuth;
    private final AdminSessionService adminSession;
    private final List<AdminNavExtension> navExtensions;
    private final ObjectProvider<ConsultantSubjectProvider> consultantSubjectProvider;
    private final ThemeService themeService;

    @Value("${genesis.environment:dev}")
    private String environment;

    @Value("${genesis.api-key}")
    private String apiKey;

    @Value("${spring.application.name:}")
    private String applicationName;

    /**
     * genesis-os is the platform itself — it has no tenant layer above it, so nothing
     * running there can genuinely be a tenant "customisation." Per-item origin checks
     * (ModuleOrigin, ThemeService.isActiveThemeCore, AdminNavExtension.core) only
     * distinguish brain-engine-core from "some app's own module," which correctly
     * separates genesis-brands' tenant overrides from shared platform code, but cannot
     * tell genesis-os's own classes apart from a tenant's — every app's own classes land
     * in the same unmarked path once packaged. So on genesis-os specifically, every
     * dashboard card is forced to core=true regardless of where its code physically lives.
     */
    private boolean isPlatformApp() {
        return "genesis-os".equals(applicationName);
    }

    @GetMapping("/env")
    public ResponseEntity<Map<String, String>> env(HttpServletRequest req) {
        if (!adminAuth.isAdminRequest(req) && !adminSession.hasValidSession(req)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(Map.of("env", environment.toUpperCase()));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, String>> me(HttpServletRequest req) {
        if (!adminAuth.isAdminRequest(req) && !adminSession.hasValidSession(req)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(Map.of("role", "SUPER_ADMIN"));
    }

    @GetMapping("/key")
    public ResponseEntity<Map<String, String>> key(HttpServletRequest req) {
        if (!adminAuth.isAdminRequest(req) && !adminSession.hasValidSession(req)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(Map.of("key", apiKey));
    }

    @GetMapping("/nav-extensions")
    public ResponseEntity<List<Map<String, Object>>> navExtensions(HttpServletRequest req) {
        if (!adminAuth.isAdminRequest(req) && !adminSession.hasValidSession(req)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<Map<String, Object>> result = navExtensions.stream()
                .sorted(Comparator.comparingInt(AdminNavExtension::order))
                .map(ext -> Map.<String, Object>of("label", ext.label(), "path", ext.path(), "description", ext.description()))
                .toList();
        return ResponseEntity.ok(result);
    }

    /**
     * The dashboard's full card set — structural platform pages plus every registered
     * AdminNavExtension — as one flat, data-driven list instead of dashboard.html's old
     * hardcoded "Platform" markup plus a separately-fetched "Extensions" section. Each
     * card carries {@code core}, computed per-card rather than by static origin alone:
     * Consultant and Themes are only genuinely "customised" when a tenant has actually
     * specialized them (a tenant-authored subject provider; an imported, non-built-in
     * theme) — everything else either always ships from the platform (core=true) or is
     * classified by where its AdminNavExtension implementation was loaded from.
     */
    @GetMapping("/dashboard-cards")
    public ResponseEntity<List<DashboardCard>> dashboardCards(HttpServletRequest req) {
        if (!adminAuth.isAdminRequest(req) && !adminSession.hasValidSession(req)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        List<DashboardCard> cards = new ArrayList<>();

        ConsultantSubjectProvider subjectProvider = consultantSubjectProvider.getIfAvailable();
        if (subjectProvider != null) {
            cards.add(new DashboardCard("consultant", "Consultant",
                    "Chat with Genesis AI. Ask questions, explore strategy, or run a full engagement.",
                    "/dashboard/console", "consultant", true, isPlatformApp() || ModuleOrigin.isCore(subjectProvider.getClass())));
        }

        cards.add(new DashboardCard("knowledge", "Knowledge",
                "Browse Layer 1 sources and reasoning modules. Empty by default on a bare platform instance until ingested.",
                "/dashboard/knowledge", "knowledge", true, true));

        cards.add(new DashboardCard("training", "Training",
                "Add examples, label content, and manage training sessions to sharpen the AI's judgement.",
                "/dashboard/training", "training", true, true));

        cards.add(new DashboardCard("questionnaires", "Questionnaires",
                "Build and publish discovery questionnaires. Manage questions, flow, and scheduling.",
                "/dashboard/questionnaires", "questionnaires", true, true));

        cards.add(new DashboardCard("themes", "Themes",
                "Manage the visual theme applied to this tenant. Import theme bundles, switch the active theme instantly.",
                "/dashboard/themes", "themes", true, isPlatformApp() || themeService.isActiveThemeCore()));

        cards.add(new DashboardCard("agents", "Agents",
                "Configure which registered agents are available in the live dashboard and Playground, and whether A/B compare is enabled.",
                "/dashboard/agents", "agents", true, true));

        cards.add(new DashboardCard("page-flows", "Page Flows",
                "Assemble pages into a visual flow chart, connect next / previous / error arrows, and configure widgets in each page's layout.",
                "/dashboard/page-flows", "page-flows", true, true));

        cards.add(new DashboardCard("products", "Products",
                "Manage product catalogs and pricing tiers, selectable via Product Options widgets in page flows.",
                "/dashboard/products", "products", true, true));

        navExtensions.stream()
                .sorted(Comparator.comparingInt(AdminNavExtension::order))
                .forEach(ext -> cards.add(new DashboardCard(
                        ext.path(), ext.label(), ext.description(), ext.path(), "generic", true, isPlatformApp() || ext.core())));

        return ResponseEntity.ok(cards);
    }

    public record DashboardCard(
            String id, String label, String description, String path, String icon, boolean live, boolean core) {}
}
