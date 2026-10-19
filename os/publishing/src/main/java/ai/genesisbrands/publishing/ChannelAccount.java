package ai.genesisbrands.publishing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One social account a client connected: a Facebook Page, an Instagram account, a LinkedIn
 * profile or organisation. Tokens are stored encrypted by {@link TokenVault} and never leave
 * the server.
 */
@Entity
@Table(name = "channel_accounts",
    indexes = @Index(name = "idx_channel_accounts_client", columnList = "client_user_id"),
    uniqueConstraints = @UniqueConstraint(name = "uk_channel_account",
        columnNames = {"client_user_id", "provider", "network", "external_id"}))
@Data
@NoArgsConstructor
public class ChannelAccount {

    public static final String CONNECTED = "CONNECTED";
    /** The token expired or was revoked; the client must connect again. */
    public static final String EXPIRED = "EXPIRED";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "client_user_id", nullable = false, length = 36)
    private String clientUserId;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(nullable = false, length = 40)
    private String network;

    @Column(name = "external_id", nullable = false, length = 200)
    private String externalId;

    @Column(name = "display_name", length = 300)
    private String displayName;

    @Column(name = "access_token_enc", columnDefinition = "TEXT")
    private String accessTokenEnc;

    @Column(name = "refresh_token_enc", columnDefinition = "TEXT")
    private String refreshTokenEnc;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(nullable = false, length = 20)
    private String status = CONNECTED;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "connected_at")
    private Instant connectedAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
