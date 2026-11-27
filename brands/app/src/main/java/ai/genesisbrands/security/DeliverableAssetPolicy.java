package ai.genesisbrands.security;

import ai.genesisbrands.service.AssetAccessPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Withholds the paid deliverables from the public {@code /api/assets/**} route: brand-book PDFs
 * and logo packages (ZIPs). Clients get them from the engagement's own {@code /pdf/{direction}}
 * and {@code /logos/{direction}} endpoints, which check owner + payment. Logo and photo images
 * stay public here because the dashboard and previews render them; they are handled separately.
 */
@Component
public class DeliverableAssetPolicy implements AssetAccessPolicy {

    private final AdminAuthHelper adminAuth;
    private final AdminSessionService adminSession;

    public DeliverableAssetPolicy(AdminAuthHelper adminAuth, AdminSessionService adminSession) {
        this.adminAuth = adminAuth;
        this.adminSession = adminSession;
    }

    @Override
    public boolean allows(String blobPath, HttpServletRequest request) {
        if (!isDeliverable(blobPath)) return true;
        return adminSession.hasValidSession(request) || adminAuth.isAdminRequest(request);
    }

    static boolean isDeliverable(String blobPath) {
        String p = blobPath.toLowerCase();
        return p.startsWith("assets/brand-books/") || p.endsWith(".pdf") || p.endsWith(".zip");
    }
}
