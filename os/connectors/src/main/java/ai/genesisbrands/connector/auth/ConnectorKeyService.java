package ai.genesisbrands.connector.auth;

import ai.genesisbrands.repository.ClientUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Issues, authenticates and revokes connector keys. Plaintext keys are never persisted. */
@Service
@RequiredArgsConstructor
public class ConnectorKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** lastUsedAt is bookkeeping, not an audit trail — don't write a row on every call. */
    private static final Duration LAST_USED_RESOLUTION = Duration.ofMinutes(1);

    private final ConnectorKeyRepository repository;
    private final ClientUserRepository clientUsers;

    @Transactional
    public IssuedKey issue(String scope, String clientUserId, String label) {
        if (ConnectorKey.SCOPE_CLIENT.equals(scope)) {
            if (clientUserId == null || !clientUsers.existsById(clientUserId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A client key needs an existing client user");
            }
        } else if (ConnectorKey.SCOPE_PLATFORM.equals(scope)) {
            clientUserId = null;
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown scope: " + scope);
        }

        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String plaintext = (ConnectorKey.SCOPE_CLIENT.equals(scope) ? "gbc_" : "gbp_")
            + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);

        ConnectorKey key = new ConnectorKey();
        key.setId(UUID.randomUUID().toString());
        key.setKeyHash(hash(plaintext));
        key.setKeyPrefix(plaintext.substring(0, 12));
        key.setScope(scope);
        key.setClientUserId(clientUserId);
        key.setLabel(label == null || label.isBlank() ? null : label.strip());
        key.setCreatedAt(Instant.now());
        return new IssuedKey(plaintext, repository.save(key));
    }

    /** The live (unrevoked) key matching {@code plaintext}, if any. */
    @Transactional
    public Optional<ConnectorKey> authenticate(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return Optional.empty();
        Optional<ConnectorKey> key = repository.findByKeyHash(hash(plaintext.strip()))
            .filter(k -> k.getRevokedAt() == null);
        key.ifPresent(k -> {
            Instant now = Instant.now();
            if (k.getLastUsedAt() == null || k.getLastUsedAt().isBefore(now.minus(LAST_USED_RESOLUTION))) {
                k.setLastUsedAt(now);
                repository.save(k);
            }
        });
        return key;
    }

    public List<ConnectorKey> listAll() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    public List<ConnectorKey> listForClient(String clientUserId) {
        return repository.findAllByClientUserIdOrderByCreatedAtDesc(clientUserId);
    }

    @Transactional
    public ConnectorKey revoke(String keyId) {
        ConnectorKey key = repository.findById(keyId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Key not found"));
        if (key.getRevokedAt() == null) {
            key.setRevokedAt(Instant.now());
            repository.save(key);
        }
        return key;
    }

    /** Revokes one of the client's own keys; anyone else's key is reported as not found. */
    @Transactional
    public ConnectorKey revokeOwn(String keyId, String clientUserId) {
        repository.findById(keyId)
            .filter(k -> clientUserId.equals(k.getClientUserId()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Key not found"));
        return revoke(keyId);
    }

    static String hash(String plaintext) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code key} is the plaintext — returned exactly once, never stored. */
    public record IssuedKey(String key, ConnectorKey record) {}
}
