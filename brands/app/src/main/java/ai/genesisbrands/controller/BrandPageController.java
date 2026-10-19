package ai.genesisbrands.controller;

import ai.genesisbrands.model.ClientUser;
import ai.genesisbrands.repository.EngagementRepository;
import ai.genesisbrands.security.ClientAuthHelper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

@Controller
@RequiredArgsConstructor
public class BrandPageController {

    static final String DASHBOARD = "/live/dashboard";

    private final ClientAuthHelper clientAuthHelper;
    private final EngagementRepository engagementRepo;

    @GetMapping("/dashboard/templates")
    public String templatesPage() { return "forward:/templates.html"; }
    @GetMapping("/templates")
    public String templatesRedirect() { return "redirect:/dashboard/templates"; }

    /**
     * Where a finished brand journey lands. The results live on the {@code /live/dashboard}
     * flow; this link claims the engagement for the signed-in client (if nobody owns it yet)
     * and sends them there. A signed-out visitor signs in first and comes back here.
     */
    @GetMapping("/your-brand/{id}")
    public String yourBrand(@PathVariable String id, HttpServletRequest req) {
        Optional<ClientUser> client = clientAuthHelper.resolve(req);
        if (client.isEmpty()) {
            return "redirect:/login?next=" + URLEncoder.encode("/your-brand/" + id, StandardCharsets.UTF_8);
        }
        String clientUserId = client.get().getId();
        engagementRepo.findById(id)
            .filter(e -> e.getClientUserId() == null)
            .ifPresent(e -> {
                e.setClientUserId(clientUserId);
                e.setUpdatedAt(Instant.now());
                engagementRepo.save(e);
            });
        return "redirect:" + DASHBOARD;
    }
}
