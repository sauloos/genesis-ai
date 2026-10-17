package ai.genesisbrands.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "client_users")
@Data
@NoArgsConstructor
public class ClientUser {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column
    private String name;

    @Column(name = "password_hash")
    private String passwordHash;

    /** Google "sub" claim once this account has signed in with Google — null for
     *  password-only accounts. Unique so a Google identity never resolves to two users. */
    @Column(name = "google_sub", unique = true)
    private String googleSub;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
