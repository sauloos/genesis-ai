package ai.genesisbrands.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Agent execution (LLM calls, image generation) fails in ways callers need a readable
 * status for — not a raw stack trace. Upstream provider errors (billing limits, quota,
 * rate limits) surface here as IllegalStateException from the agent layer; this maps
 * them to an appropriate status + message instead of the default 500.
 */
@RestControllerAdvice(basePackages = "ai.genesisbrands.controller")
public class AgentExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentExceptionHandler.class);

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleAgentFailure(IllegalStateException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        log.error("Agent execution failed: {}", message, e);

        if (containsAny(message, "billing_hard_limit_reached", "billing limit")) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ErrorResponse(
                "Image generation is unavailable — the OpenAI account has reached its billing limit. Check billing settings and try again."
            ));
        }
        if (containsAny(message, "insufficient_quota", "quota")) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ErrorResponse(
                "Image generation is unavailable — the OpenAI account is out of quota. Check billing settings and try again."
            ));
        }
        if (containsAny(message, "rate_limit")) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new ErrorResponse(
                "Image generation is temporarily rate-limited. Please try again in a moment."
            ));
        }

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse(
            "Agent execution failed: " + message
        ));
    }

    private static boolean containsAny(String haystack, String... needles) {
        String lower = haystack.toLowerCase();
        for (String needle : needles) {
            if (lower.contains(needle)) return true;
        }
        return false;
    }

    public record ErrorResponse(String message) {}
}
