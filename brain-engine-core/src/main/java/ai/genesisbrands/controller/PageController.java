package ai.genesisbrands.controller;

import ai.genesisbrands.model.PageFlow;
import ai.genesisbrands.repository.PageFlowRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

@Controller
public class PageController {

    private static final String SLUG_MARKER = "<!--PF_SLUG_INJECT-->";
    private static final String THEME_MARKER = "<!--PF_THEME_INJECT-->";
    private static final String DEFAULT_THEME_STYLES_URL = "/api/themes/active/styles.css";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PageFlowRepository pageFlowRepo;
    private final String flowRuntimeTemplate = readClasspathResource("static/flow-runtime.html");

    @Value("${genesis.google-oauth.client-id:}")
    private String googleOauthClientId;

    public PageController(PageFlowRepository pageFlowRepo) {
        this.pageFlowRepo = pageFlowRepo;
    }

    private static String readClasspathResource(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Server-rendered so the resolved slug (and root prefix) reach the client even when
     * this page is reached via PageFlowRouteFilter's forward: a forward never updates the
     * browser's own window.location, so a client-side read of location.search always sees
     * null for a /live/<slug>-style request. window.__PF_SLUG__/__PF_ROOT_PREFIX__ are the
     * one channel that works both for that forwarded hit and for a direct ?slug=...
     * navigation. rootPrefix disambiguates a slug shared by two live flows under different
     * prefixes (see PageFlowRepository#findLiveByRoute) — omitted (null) means the default.
     */
    @GetMapping("/flow-runtime.html")
    public ResponseEntity<String> flowRuntime(
            @RequestParam(required = false) String slug, @RequestParam(required = false) String rootPrefix) {
        String slugJson;
        String rootPrefixJson;
        String googleClientIdJson;
        try {
            slugJson = JSON.writeValueAsString(slug).replace("</", "<\\/");
            rootPrefixJson = JSON.writeValueAsString(rootPrefix).replace("</", "<\\/");
            googleClientIdJson = JSON.writeValueAsString(googleOauthClientId.isBlank() ? null : googleOauthClientId).replace("</", "<\\/");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String themeStylesUrl = slug == null ? DEFAULT_THEME_STYLES_URL
            : pageFlowRepo.findLiveByRoute(rootPrefix, slug).map(PageController::themeStylesUrl).orElse(DEFAULT_THEME_STYLES_URL);
        String html = flowRuntimeTemplate
            .replace(SLUG_MARKER, "<script>window.__PF_SLUG__ = " + slugJson + "; window.__PF_ROOT_PREFIX__ = " + rootPrefixJson
                + "; window.__PF_GOOGLE_CLIENT_ID__ = " + googleClientIdJson + ";</script>")
            .replace(THEME_MARKER, "<link rel=\"stylesheet\" href=\"" + themeStylesUrl + "\">");
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    private static String themeStylesUrl(PageFlow flow) {
        return flow.getThemeKey() == null ? DEFAULT_THEME_STYLES_URL : "/api/themes/" + flow.getThemeKey() + "/styles.css";
    }

    @GetMapping("/dashboard")
    public String dashboardPage() {
        return "forward:/dashboard.html";
    }

    @GetMapping("/dashboard/console")
    public String consolePage() { return "forward:/console.html"; }
    @GetMapping("/console")
    public String consoleRedirect() { return "redirect:/dashboard/console"; }

    @GetMapping("/dashboard/training")
    public String trainingPage() { return "forward:/training.html"; }
    @GetMapping("/training")
    public String trainingRedirect() { return "redirect:/dashboard/training"; }

    @GetMapping("/dashboard/questionnaires")
    public String questionnairesPage() { return "forward:/questionnaires.html"; }
    @GetMapping("/questionnaires")
    public String questionnairesRedirect() { return "redirect:/dashboard/questionnaires"; }

    @GetMapping("/dashboard/knowledge")
    public String knowledgePage() { return "forward:/knowledge.html"; }
    @GetMapping("/knowledge")
    public String knowledgeRedirect() { return "redirect:/dashboard/knowledge"; }

    @GetMapping("/dashboard/themes")
    public String themesPage() { return "forward:/themes.html"; }
    @GetMapping("/themes")
    public String themesRedirect() { return "redirect:/dashboard/themes"; }

    @GetMapping("/dashboard/agents")
    public String agentsPage() { return "forward:/agents.html"; }
    @GetMapping("/agents")
    public String agentsRedirect() { return "redirect:/dashboard/agents"; }

    @GetMapping("/dashboard/page-flows")
    public String pageFlowsPage() { return "forward:/page-flows.html"; }
    @GetMapping("/page-flows")
    public String pageFlowsRedirect() { return "redirect:/dashboard/page-flows"; }

    @GetMapping("/dashboard/playground")
    public String playgroundPage() { return "forward:/playground.html"; }
    @GetMapping("/playground")
    public String playgroundRedirect() { return "redirect:/dashboard/playground"; }

    @GetMapping("/flow/{slug}")
    public String flowRuntimeLegacyRedirect(@PathVariable String slug) { return "redirect:/live/" + slug; }

    @GetMapping("/login")
    public String loginPage() {
        return "forward:/login.html";
    }

    @GetMapping("/register")
    public String registerPage() {
        return "forward:/register.html";
    }
}
