package com.genesisai.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "conversation_messages")
@Data
@NoArgsConstructor
public class ConversationMessage {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "brand_id", nullable = false, length = 36)
    private String brandId;

    @Column(nullable = false, length = 10)
    private String role;  // "user" or "assistant"

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
