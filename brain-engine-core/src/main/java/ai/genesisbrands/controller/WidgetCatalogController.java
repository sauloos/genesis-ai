package ai.genesisbrands.controller;

import ai.genesisbrands.platform.PageLayout;
import ai.genesisbrands.security.AdminAuthHelper;
import ai.genesisbrands.security.AdminSessionService;
import ai.genesisbrands.service.WidgetCatalogService;
import ai.genesisbrands.service.WidgetCatalogService.WidgetTypeEntry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class WidgetCatalogController {

    private final AdminAuthHelper adminAuth;
    private final AdminSessionService adminSession;
    private final WidgetCatalogService widgetCatalogService;

    @GetMapping("/api/admin/widget-types")
    public ResponseEntity<List<WidgetTypeEntry>> widgetTypes(HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(widgetCatalogService.listWidgetTypes());
    }

    @GetMapping("/api/admin/page-layouts")
    public ResponseEntity<List<PageLayout>> pageLayouts(HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(widgetCatalogService.listLayouts());
    }

    private boolean authorized(HttpServletRequest req) {
        return adminAuth.isAdminRequest(req) || adminSession.hasValidSession(req);
    }
}
