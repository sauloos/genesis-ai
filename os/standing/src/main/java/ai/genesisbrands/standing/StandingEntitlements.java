package ai.genesisbrands.standing;

import ai.genesisbrands.model.Payment;
import ai.genesisbrands.repository.PaymentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * Whether a client has bought what an offer requires. An offer with no required products is
 * open to every client of the tenant; otherwise the client needs a succeeded payment whose
 * cart held one of those products.
 *
 * <p>Not yet tracked: renewals and cancellations of recurring products. A client who paid once
 * stays entitled until payments record subscription state.
 */
@Service
public class StandingEntitlements {

    private final PaymentRepository payments;
    private final ObjectMapper objectMapper;

    public StandingEntitlements(PaymentRepository payments, ObjectMapper objectMapper) {
        this.payments = payments;
        this.objectMapper = objectMapper;
    }

    public boolean entitled(String clientUserId, StandingAgentOffer offer) {
        Set<String> required = StandingCatalog.splitCsv(offer.getRequiredProducts());
        if (required.isEmpty()) return true;
        if (clientUserId == null) return false;
        Set<String> owned = new HashSet<>();
        for (Payment p : payments.findByClientUserIdAndStatus(clientUserId, Payment.Status.SUCCEEDED)) {
            owned.addAll(productIds(p.getCartJson()));
        }
        return required.stream().anyMatch(owned::contains);
    }

    /** The product ids in a payment's cart snapshot (a JSON array of cart items). */
    Set<String> productIds(String cartJson) {
        Set<String> out = new HashSet<>();
        if (cartJson == null || cartJson.isBlank()) return out;
        try {
            JsonNode cart = objectMapper.readTree(cartJson);
            if (cart.isArray()) {
                cart.forEach(item -> {
                    JsonNode id = item.get("productId");
                    if (id != null && id.isTextual()) out.add(id.asText());
                });
            }
        } catch (Exception ignored) {
            // An unreadable snapshot grants nothing.
        }
        return out;
    }
}
