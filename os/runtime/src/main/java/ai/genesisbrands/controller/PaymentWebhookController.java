package ai.genesisbrands.controller;

import ai.genesisbrands.model.Payment;
import ai.genesisbrands.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Stripe delivers these server-to-server, with no X-Api-Key header — excluded from
 * ApiKeyFilter, authenticity is instead verified via the Stripe-Signature header against
 * the mode-specific webhook secret (see PaymentService.handleWebhook). Test and live
 * events are delivered to separate routes so the mode never has to be guessed.
 */
@RestController
@RequestMapping("/api/public/payments/webhook")
@RequiredArgsConstructor
public class PaymentWebhookController {

    private final PaymentService paymentService;

    @PostMapping("/stripe/test")
    public ResponseEntity<?> stripeTest(HttpServletRequest req) throws IOException {
        return handle(Payment.Mode.TEST, req);
    }

    @PostMapping("/stripe/live")
    public ResponseEntity<?> stripeLive(HttpServletRequest req) throws IOException {
        return handle(Payment.Mode.LIVE, req);
    }

    private ResponseEntity<?> handle(Payment.Mode mode, HttpServletRequest req) throws IOException {
        String payload = readBody(req);
        String signature = req.getHeader("Stripe-Signature");
        try {
            paymentService.handleWebhook(mode, payload, signature);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(e.getMessage());
        }
    }

    private String readBody(HttpServletRequest req) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(req.getInputStream(), StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int read;
            while ((read = reader.read(buf)) != -1) {
                sb.append(buf, 0, read);
            }
        }
        return sb.toString();
    }
}
