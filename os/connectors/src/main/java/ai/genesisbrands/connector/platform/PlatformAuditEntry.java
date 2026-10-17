package ai.genesisbrands.connector.platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One Platform connector call: which key ran which admin operation, against which resolved
 * path, and how it ended. Request and response bodies are deliberately not stored — they can
 * carry secrets (e.g. payment config) that an audit log must not become a second copy of.
 */
@Entity
@Table(name = "platform_audit_log")
@Data
@NoArgsConstructor
public class PlatformAuditEntry {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "key_id", nullable = false, length = 36)
    private String keyId;

    @Column(name = "key_label", length = 120)
    private String keyLabel;

    @Column(name = "operation_id", nullable = false, length = 160)
    private String operationId;

    @Column(nullable = false, length = 10)
    private String method;

    @Column(nullable = false, length = 1000)
    private String path;

    /** HTTP status of the underlying admin call; 0 when it never completed. */
    @Column(nullable = false)
    private int status;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(length = 500)
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
