package ai.genesisbrands.controller;

import ai.genesisbrands.service.CartService;
import ai.genesisbrands.service.CartService.CartItem;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Public, per-widget selection state for the ProductOptionsWidget — lets a visitor pick a
 * tier (pre-payment intent, not a committed purchase) and have it survive a page reload.
 * Scoped by widgetId so a page with several widget instances (e.g. one-time package +
 * subscription) tracks each choice independently. Each selection is upserted into the
 * FlowSession's shared cart (see CartService) rather than a widget-private key, so a
 * later payment widget on a different page can read every selection made across the
 * whole session and charge the total in one checkout.
 */
@RestController
@RequestMapping("/api/public/flow-sessions/{token}/widgets/{widgetId}/selection")
@RequiredArgsConstructor
public class ProductSelectionController {

    private final CartService cartService;

    @GetMapping
    public ResponseEntity<?> get(@PathVariable String token, @PathVariable String widgetId) {
        List<CartItem> cart;
        try {
            cart = cartService.getCart(token);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
        return ResponseEntity.ok(cart.stream()
            .filter(item -> item.widgetId().equals(widgetId))
            .findFirst()
            .map(this::toResponse)
            .orElse(new SelectionResponse(null, null, null, null, null, null, null)));
    }

    @PostMapping
    public ResponseEntity<?> select(@PathVariable String token, @PathVariable String widgetId,
                                     @RequestBody SelectRequest body) {
        if (body.optionId() == null || body.optionId().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("optionId is required"));
        }
        if (body.productId() == null || body.productId().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("productId is required"));
        }

        List<CartItem> cart;
        try {
            cart = cartService.upsert(token, widgetId, body.productId(), body.optionId());
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
        }
        return ResponseEntity.ok(cart.stream()
            .filter(item -> item.widgetId().equals(widgetId))
            .findFirst()
            .map(this::toResponse)
            .orElseThrow());
    }

    private SelectionResponse toResponse(CartItem item) {
        return new SelectionResponse(item.optionId(), item.productId(), item.priceCents(),
            item.currency(), item.billingInterval(), item.optionName(), item.productName());
    }

    public record SelectRequest(String productId, String optionId) {}
    public record SelectionResponse(String optionId, String productId, Long priceCents, String currency,
                                     String billingInterval, String optionName, String productName) {}
    public record ErrorResponse(String message) {}
}
