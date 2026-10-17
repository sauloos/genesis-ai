package ai.genesisbrands.controller;

import ai.genesisbrands.model.ClientUser;
import ai.genesisbrands.model.FlowSession;
import ai.genesisbrands.model.Payment;
import ai.genesisbrands.model.PageFlow;
import ai.genesisbrands.repository.PageFlowRepository;
import ai.genesisbrands.security.ClientAuthHelper;
import ai.genesisbrands.service.CartService;
import ai.genesisbrands.service.CartService.CartItem;
import ai.genesisbrands.service.FlowSessionService;
import ai.genesisbrands.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Public checkout endpoints for the PaymentWidget. Charges the FlowSession's whole cart
 * (every selection made across any number of ProductOptionsWidget instances, via
 * CartService) in one Stripe Checkout — the payment widget itself carries no product
 * config, so a single instance on its own page settles everything picked earlier in
 * the flow.
 */
@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final FlowSessionService flowSessionService;
    private final PaymentService paymentService;
    private final CartService cartService;
    private final PageFlowRepository pageFlowRepo;
    private final ClientAuthHelper clientAuthHelper;

    @GetMapping("/api/public/payments/mode")
    public ResponseEntity<?> mode() {
        return ResponseEntity.ok(Map.of("mode", paymentService.currentMode().name()));
    }

    @GetMapping("/api/public/flow-sessions/{token}/cart")
    public ResponseEntity<?> cart(@PathVariable String token) {
        List<CartItem> cart;
        try {
            cart = cartService.getCart(token);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
        long totalCents = cart.stream().mapToLong(CartItem::priceCents).sum();
        String currency = cart.isEmpty() ? null : cart.get(0).currency();
        return ResponseEntity.ok(new CartResponse(cart, totalCents, currency));
    }

    @PostMapping("/api/public/flow-sessions/{token}/widgets/{widgetId}/checkout")
    public ResponseEntity<?> checkout(@PathVariable String token, @PathVariable String widgetId,
                                       HttpServletRequest req) {
        FlowSession session;
        List<CartItem> cart;
        try {
            session = flowSessionService.get(token);
            cart = cartService.getCart(token);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
        if (cart.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("Your cart is empty"));
        }

        String host = req.getHeader("Host");
        PageFlow flow = pageFlowRepo.findById(session.getPageFlowId()).orElse(null);
        String livePath = flow != null ? flow.getLivePath() : "/";
        String baseUrl = "https://" + host + livePath;
        String successUrl = baseUrl + "?payment=success";
        String cancelUrl = baseUrl + "?payment=canceled";

        String clientUserId = clientAuthHelper.resolve(req).map(ClientUser::getId).orElse(null);

        try {
            PaymentService.CheckoutOutcome outcome = paymentService.startCheckout(
                token, widgetId, clientUserId, cart, successUrl, cancelUrl);
            return ResponseEntity.ok(toResponse(outcome.payment(), outcome.checkoutUrl()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/api/public/flow-sessions/{token}/widgets/{widgetId}/checkout/status")
    public ResponseEntity<?> status(@PathVariable String token, @PathVariable String widgetId) {
        try {
            flowSessionService.get(token);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
        Payment payment = paymentService.status(token, widgetId);
        return ResponseEntity.ok(payment == null ? Map.of("status", "NONE") : toResponse(payment, null));
    }

    private PaymentResponse toResponse(Payment payment, String checkoutUrl) {
        return new PaymentResponse(payment.getId(), payment.getStatus().name(), payment.getMode().name(),
            payment.getAmountCents(), payment.getCurrency(), checkoutUrl);
    }

    public record CartResponse(List<CartItem> items, long totalCents, String currency) {}
    public record PaymentResponse(String paymentId, String status, String mode, long amountCents,
                                   String currency, String checkoutUrl) {}
    public record ErrorResponse(String message) {}
}
