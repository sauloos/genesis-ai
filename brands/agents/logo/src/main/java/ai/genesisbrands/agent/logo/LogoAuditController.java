package ai.genesisbrands.agent.logo;

import ai.genesisbrands.security.AdminAuthHelper;
import ai.genesisbrands.security.AdminSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

/**
 * Admin-only: assess a logo directly, with the brand context given as text. For checking the
 * audit on real logos before clients see it; spends one Opus vision call.
 */
@RestController
@RequestMapping("/api/admin/logo-audit")
public class LogoAuditController {

    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private final LogoAudit audit;
    private final AdminAuthHelper adminAuth;
    private final AdminSessionService adminSession;

    public LogoAuditController(LogoAudit audit, AdminAuthHelper adminAuth, AdminSessionService adminSession) {
        this.audit = audit;
        this.adminAuth = adminAuth;
        this.adminSession = adminSession;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public LogoAssessment assess(@RequestPart("image") MultipartFile image,
                                 @RequestParam(value = "context", required = false) String context,
                                 @RequestParam(value = "note", required = false) String note,
                                 @RequestParam(value = "intent", required = false) String intent,
                                 HttpServletRequest req) throws IOException {
        if (!adminAuth.isAdminRequest(req) && !adminSession.hasValidSession(req)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Admin credentials required");
        }
        if (image.isEmpty() || image.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Send a logo image up to 5MB");
        }
        byte[] bytes = image.getBytes();
        String type = ImageTypes.sniff(bytes);
        if (!LogoAudit.canView(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Send a PNG, JPEG, WebP or GIF");
        }
        return audit.assess(bytes, type, context, note, intent);
    }
}
