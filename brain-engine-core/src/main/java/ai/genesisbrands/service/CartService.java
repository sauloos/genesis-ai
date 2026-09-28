package ai.genesisbrands.service;

import ai.genesisbrands.model.FlowSession;
import ai.genesisbrands.model.Product;
import ai.genesisbrands.model.ProductOption;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A FlowSession's shopping cart — one line item per ProductOptionsWidget selection,
 * keyed by widgetId so re-selecting on one widget replaces only its own line rather
 * than accumulating duplicates. Stored as a single "cart" array under
 * FlowSession.contextJson. Shared by ProductSelectionController (writes one item at a
 * time as the visitor picks tiers) and PaymentController (reads the whole cart to
 * render a summary and charge the total) so the JSON-context plumbing lives in one place.
 */
@Service
@RequiredArgsConstructor
public class CartService {

    private static final String CONTEXT_KEY = "cart";

    private final FlowSessionService flowSessionService;
    private final ProductService productService;
    private final ObjectMapper objectMapper;

    public record CartItem(String widgetId, String productId, String optionId, String productName,
                            String optionName, long priceCents, String currency, String billingInterval) {}

    public List<CartItem> getCart(String token) {
        FlowSession session = flowSessionService.get(token);
        return readCart(session);
    }

    /** Validates the product/option are active, upserts a CartItem for this widgetId
     *  (replacing any prior selection from the same widget), and persists the result. */
    public List<CartItem> upsert(String token, String widgetId, String productId, String optionId) {
        Product product = productService.getProduct(productId);
        ProductOption option = productService.getOption(productId, optionId);
        if (!product.isActive() || !option.isActive()) {
            throw new IllegalStateException("This option is not currently available");
        }

        FlowSession session = flowSessionService.get(token);
        List<CartItem> cart = new ArrayList<>(readCart(session));
        cart.removeIf(item -> item.widgetId().equals(widgetId));
        cart.add(new CartItem(widgetId, product.getId(), option.getId(), product.getName(),
            option.getName(), option.getPriceCents(), option.getCurrency(), option.getBillingInterval()));

        writeCart(token, cart);
        return cart;
    }

    private List<CartItem> readCart(FlowSession session) {
        if (session.getContextJson() == null || session.getContextJson().isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> context = objectMapper.readValue(session.getContextJson(), new TypeReference<Map<String, Object>>() {});
            Object raw = context.get(CONTEXT_KEY);
            if (raw == null) return List.of();
            return objectMapper.convertValue(raw, new TypeReference<List<CartItem>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private void writeCart(String token, List<CartItem> cart) {
        try {
            String patch = objectMapper.writeValueAsString(Map.of(CONTEXT_KEY, cart));
            flowSessionService.updateContext(token, patch);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist cart", e);
        }
    }
}
