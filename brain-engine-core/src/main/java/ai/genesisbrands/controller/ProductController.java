package ai.genesisbrands.controller;

import ai.genesisbrands.model.Product;
import ai.genesisbrands.model.ProductOption;
import ai.genesisbrands.security.AdminAuthHelper;
import ai.genesisbrands.security.AdminSessionService;
import ai.genesisbrands.service.ProductService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Generic, tenant-agnostic Product/ProductOption catalog — a Product (e.g. a one-time
 * package or a recurring subscription) has an ordered list of Options (tiers). Reads are
 * gated only by the shared X-Api-Key (ApiKeyFilter), same as QuestionnaireController,
 * since the ProductOptionsWidget and the Page Flow builder both need to read this without
 * an admin session. Mutations require a real admin session/basic-auth (authorized(req)),
 * same as PageWidgetController.
 */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final AdminAuthHelper adminAuth;
    private final AdminSessionService adminSession;

    // ── Reads (open — API-key only) ──────────────────────────────────────────

    @GetMapping
    public List<Product> list(@RequestParam(defaultValue = "false") boolean all) {
        return productService.listProducts(all);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        try {
            Product product = productService.getProduct(id);
            List<ProductOption> options = productService.listOptions(id);
            return ResponseEntity.ok(new ProductDetail(product, options));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
    }

    // ── Writes (admin-gated) ─────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<?> create(@RequestBody ProductRequest body, HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(productService.createProduct(body.name(), body.description(), body.type()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody ProductRequest body, HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            return ResponseEntity.ok(productService.updateProduct(id, body.name(), body.description(), body.type(), body.active()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id, HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        productService.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/options")
    public ResponseEntity<?> addOption(@PathVariable String id, @RequestBody OptionRequest body, HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            return ResponseEntity.ok(productService.addOption(id, body.name(), body.description(), body.priceCents(),
                body.currency(), body.billingInterval(), body.featuresJson(), body.active()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
    }

    @PutMapping("/{id}/options/{optionId}")
    public ResponseEntity<?> updateOption(@PathVariable String id, @PathVariable String optionId,
                                           @RequestBody OptionRequest body, HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            return ResponseEntity.ok(productService.updateOption(id, optionId, body.name(), body.description(),
                body.priceCents(), body.currency(), body.billingInterval(), body.featuresJson(), body.active()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
    }

    @DeleteMapping("/{id}/options/{optionId}")
    public ResponseEntity<?> deleteOption(@PathVariable String id, @PathVariable String optionId, HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            productService.deleteOption(id, optionId);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/{id}/options/reorder")
    public ResponseEntity<?> reorderOptions(@PathVariable String id, @RequestBody ReorderRequest body, HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        productService.reorderOptions(id, body.orderedOptionIds());
        return ResponseEntity.ok(productService.listOptions(id));
    }

    private boolean authorized(HttpServletRequest req) {
        return adminAuth.isAdminRequest(req) || adminSession.hasValidSession(req);
    }

    public record ProductDetail(Product product, List<ProductOption> options) {}
    public record ProductRequest(String name, String description, Product.Type type, boolean active) {}
    public record OptionRequest(String name, String description, long priceCents, String currency,
                                 String billingInterval, String featuresJson, boolean active) {}
    public record ReorderRequest(List<String> orderedOptionIds) {}
    public record ErrorResponse(String message) {}
}
