package ai.genesisbrands.controller;

import ai.genesisbrands.model.WaitlistEntry;
import ai.genesisbrands.repository.WaitlistEntryRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Public beta-signup endpoint for the holding page. Deliberately excluded from
 * ApiKeyFilter (see ApiKeyFilter) since it's called from an unauthenticated
 * page with no API key available to it.
 */
@RestController
@RequestMapping("/api/waitlist")
@RequiredArgsConstructor
@Tag(name = "Waitlist", description = "Public beta signup")
public class WaitlistController {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final WaitlistEntryRepository repository;

    @PostMapping
    @Operation(summary = "Join the beta waitlist with an email address")
    public ResponseEntity<Map<String, String>> join(@RequestBody SignupRequest req) {
        String email = req.email() == null ? "" : req.email().trim().toLowerCase();

        if (!EMAIL.matcher(email).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Please enter a valid email address."));
        }

        if (repository.existsByEmailIgnoreCase(email)) {
            return ResponseEntity.ok(Map.of("message", "You're already on the list."));
        }

        WaitlistEntry entry = new WaitlistEntry();
        entry.setId(UUID.randomUUID().toString());
        entry.setEmail(email);
        repository.save(entry);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("message", "You're on the list."));
    }

    public record SignupRequest(String email) {}
}
