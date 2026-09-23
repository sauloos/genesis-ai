package ai.genesisbrands.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A widget placed into one of a Page's chosen layout's named slots. widgetType
 * matches a registered WidgetDescriptor.widgetType(); configJson is Jackson-serialized
 * values keyed by that descriptor's WidgetConfigOption.key (same pattern as
 * QuestionnaireQuestion.configJson).
 */
@Entity
@Table(name = "page_widgets")
@Data
@NoArgsConstructor
public class PageWidget {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "page_id", nullable = false, length = 36)
    private String pageId;

    @Column(name = "slot_key", nullable = false, length = 64)
    private String slotKey;

    @Column(name = "order_in_slot", nullable = false)
    private int orderInSlot;

    @Column(name = "widget_type", nullable = false, length = 64)
    private String widgetType;

    @Column(length = 255)
    private String label;

    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
