package ai.genesisbrands.controller;

import ai.genesisbrands.model.ClientUser;
import ai.genesisbrands.model.FlowSession;
import ai.genesisbrands.model.Payment;
import ai.genesisbrands.model.PageFlow;
import ai.genesisbrands.model.ProductOption;
import ai.genesisbrands.repository.PageFlowRepository;
import ai.genesisbrands.security.ClientAuthHelper;
import ai.genesisbrands.service.FlowSessionService;
import ai.genesisbrands.service.PaymentService;
import ai.genesisbrands.service.ProductService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Public checkout endpoints for the PaymentWidget. Charges whatever was last selected on
 * a sibling ProductOptionsWidget (identified by sourceWidgetId, read out of the same
 * FlowSession.contextJson key ProductSelectionController writes) — the payment widget
 * itself carries no product config, so one payment widget instance can sit below any
 * productOptions widget on the page.
 */
@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final FlowSessionService flowSessionService;
    private final PaymentService paymentService;
    private final ProductService productService;
    private final PageFlowRepository pageFlowRepo;
    private final ClientAuthHelper clientAuthHelper;
    private final ObjectMapper objectMapper;

    @GetMapping("/api/public/payments/mode")
    public ResponseEntity<?> mode() {
        return ResponseEntity.ok(Map.of("mode", paymentService.currentMode().name()));
    }

    @PostMapping("/api/public/flow-sessions/{token}/widgets/{widgetId}/checkout")
    public ResponseEntity<?> checkout(@PathVariable String token, @PathVariable String widgetId,
                                       @RequestBody CheckoutRequest body, HttpServletRequest req) {
        FlowSession session;
        try {
            session = flowSessionService.get(token);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
        if (body.sourceWidgetId() == null || body.sourceWidgetId().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("sourceWidgetId is required"));
        }
        Object selectionObj = contextOf(session).get("product-selection:" + body.sourceWidgetId());
        if (selectionObj == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("No product option has been selected yet"));
        }

        Map<String, Object> selection = readSelection(session, body.sourceWidgetId());
        String productId = (String) selection.get("productId");
        String optionId = (String) selection.get("optionId");
        if (productId == null || optionId == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("Selected option is no longer available"));
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
                token, widgetId, clientUserId, productId, optionId, successUrl, cancelUrl);
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

    private Map<String, Object> readSelection(FlowSession session, String sourceWidgetId) {
        // ProductSelectionController persists only the bare optionId under this context
        // key; re-resolve the rest via its own GET route's logic isn't reachable here, so
        // duplicate the minimal lookup (optionId -> productId) directly off the stored id.
        Object optionIdObj = contextOf(session).get("product-selection:" + sourceWidgetId);
        String optionId = optionIdObj == null ? null : String.valueOf(optionIdObj);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("optionId", optionId);
        if (optionId != null) {
            productOf(optionId).ifPresent(productId -> result.put("productId", productId));
        }
        return result;
    }

    private java.util.Optional<String> productOf(String optionId) {
        return productService.findOptionById(optionId).map(ai.genesisbrands.model.ProductOption::getProductId);
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

    private PaymentResponse toResponse(Payment payment, String checkoutUrl) {
        return new PaymentResponse(payment.getId(), payment.getStatus().name(), payment.getMode().name(),
            payment.getAmountCents(), payment.getCurrency(), checkoutUrl);
    }

    public record CheckoutRequest(String sourceWidgetId) {}
    public record PaymentResponse(String paymentId, String status, String mode, long amountCents,
                                   String currency, String checkoutUrl) {}
    public record ErrorResponse(String message) {}
}
