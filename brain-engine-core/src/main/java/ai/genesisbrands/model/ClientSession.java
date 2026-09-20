package ai.genesisbrands.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "client_sessions")
@Data
@NoArgsConstructor
public class ClientSession {

    @Id
    @Column(length = 36)
    private String token;

    @Column(name = "client_user_id", length = 36, nullable = false)
    private String clientUserId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
