package ai.genesisbrands.connector.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A credential for the external connectors. Only its SHA-256 hash is stored — the plaintext
 * is shown once, at issue time. A {@code CLIENT} key is bound to one client user and opens
 * that client's Agent and Brain connectors; a {@code PLATFORM} key is admin-issued, bound to
 * no client, and opens only the Platform connector. The two never overlap.
 */
@Entity
@Table(name = "connector_keys")
@Data
@NoArgsConstructor
public class ConnectorKey {

    public static final String SCOPE_CLIENT = "CLIENT";
    public static final String SCOPE_PLATFORM = "PLATFORM";

    @Id
    @Column(length = 36)
    private String id;

    @JsonIgnore
    @Column(name = "key_hash", nullable = false, unique = true, length = 64)
    private String keyHash;

    /** First characters of the key, safe to display so a holder can tell keys apart. */
    @Column(name = "key_prefix", nullable = false, length = 16)
    private String keyPrefix;

    /** {@link #SCOPE_CLIENT} or {@link #SCOPE_PLATFORM} — a plain string, not a DB enum, so a
     *  future scope never needs a column-widening fixer. */
    @Column(nullable = false, length = 16)
    private String scope;

    @Column(name = "client_user_id", length = 36)
    private String clientUserId;

    @Column(length = 120)
    private String label;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;
}
