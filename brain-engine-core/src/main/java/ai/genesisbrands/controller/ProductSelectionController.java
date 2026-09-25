package ai.genesisbrands.controller;

import ai.genesisbrands.model.FlowSession;
import ai.genesisbrands.model.Product;
import ai.genesisbrands.model.ProductOption;
import ai.genesisbrands.service.FlowSessionService;
import ai.genesisbrands.service.ProductService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Public, per-widget selection state for the ProductOptionsWidget — lets a visitor pick a
 * tier (pre-payment intent, not a committed purchase) and have it survive a page reload.
 * Scoped by widgetId so a page with several widget instances (e.g. one-time package +
 * subscription) tracks each choice independently. Stored in FlowSession.contextJson under
 * "product-selection:"+widgetId — freely re-selectable, not first-write-wins.
 */
@RestController
@RequestMapping("/api/public/flow-sessions/{token}/widgets/{widgetId}/selection")
@RequiredArgsConstructor
public class ProductSelectionController {

    private final FlowSessionService flowSessionService;
    private final ProductService productService;
    private final ObjectMapper objectMapper;

    @GetMapping
    public ResponseEntity<?> get(@PathVariable String token, @PathVariable String widgetId) {
        FlowSession session;
        try {
            session = flowSessionService.get(token);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
        Object optionIdObj = contextOf(session).get("product-selection:" + widgetId);
        if (optionIdObj == null) {
            return ResponseEntity.ok(new SelectionResponse(null, null, null, null, null, null, null));
        }
        String optionId = String.valueOf(optionIdObj);
        // Re-resolve the option/product here (rather than just echoing the id back) so a
        // sibling payment widget can read productId/price/name straight off this response
        // without needing its own product config — if the option was since deleted, fall
        // back to just the bare id so the caller can still tell a selection was made.
        ProductOption option = productService.findOptionById(optionId).orElse(null);
        if (option == null) {
            return ResponseEntity.ok(new SelectionResponse(optionId, null, null, null, null, null, null));
        }
        Product product = productService.getProduct(option.getProductId());
        return ResponseEntity.ok(new SelectionResponse(optionId, option.getProductId(), option.getPriceCents(),
            option.getCurrency(), option.getBillingInterval(), option.getName(), product.getName()));
    }

    @PostMapping
    public ResponseEntity<?> select(@PathVariable String token, @PathVariable String widgetId,
                                     @RequestBody SelectRequest body) {
        try {
            flowSessionService.get(token);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
        if (body.optionId() == null || body.optionId().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("optionId is required"));
        }
        if (body.productId() == null || body.productId().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("productId is required"));
        }

        Product product;
        ProductOption option;
        try {
            product = productService.getProduct(body.productId());
            option = productService.getOption(body.productId(), body.optionId());
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
        if (!product.isActive() || !option.isActive()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("This option is not currently available"));
        }

        try {
            String patch = objectMapper.writeValueAsString(Map.of("product-selection:" + widgetId, option.getId()));
            flowSessionService.updateContext(token, patch);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse("Failed to persist selection"));
        }
        return ResponseEntity.ok(new SelectionResponse(option.getId(), option.getProductId(), option.getPriceCents(),
            option.getCurrency(), option.getBillingInterval(), option.getName(), product.getName()));
    }

    private Map<String, Object> contextOf(FlowSession session) {
        if (session.getContextJson() == null || session.getContextJson().isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(objectMapper.readValue(session.getContextJson(), new TypeReference<Map<String, Object>>() {}));
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    public record SelectRequest(String productId, String optionId) {}
    public record SelectionResponse(String optionId, String productId, Long priceCents, String currency,
                                     String billingInterval, String optionName, String productName) {}
    public record ErrorResponse(String message) {}
}
