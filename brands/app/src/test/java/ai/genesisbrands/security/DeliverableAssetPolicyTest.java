package ai.genesisbrands.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeliverableAssetPolicyTest {

    private final AdminAuthHelper adminAuth = mock(AdminAuthHelper.class);
    private final AdminSessionService adminSession = mock(AdminSessionService.class);
    private final DeliverableAssetPolicy policy = new DeliverableAssetPolicy(adminAuth, adminSession);
    private final HttpServletRequest req = mock(HttpServletRequest.class);

    @Test
    void publicCannotFetchPdfsOrZips() {
        assertFalse(policy.allows("assets/brand-books/abc/ANCHORED-1.pdf", req));
        assertFalse(policy.allows("assets/logos/abc/ANCHORED-logo-package.zip", req));
        assertFalse(policy.allows("Assets/Logos/abc/x.ZIP", req));
    }

    @Test
    void publicCannotFetchStoredCovers() {
        assertFalse(policy.allows("previews/e1/anchored-ab12.jpg", req));
    }

    @Test
    void publicCanStillFetchImages() {
        assertTrue(policy.allows("assets/logos/abc/ANCHORED-1.png", req));
        assertTrue(policy.allows("uploads/abc/logo.jpg", req));
    }

    @Test
    void adminCanFetchDeliverables() {
        when(adminAuth.isAdminRequest(req)).thenReturn(true);
        assertTrue(policy.allows("assets/brand-books/abc/ANCHORED-1.pdf", req));
    }
}
