package ai.genesisbrands.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Drops the stale {@code conversation_messages_source_check} CHECK constraint that Hibernate
 * generated when the {@code source} column was first created, before
 * {@link ConversationMessage.Source} gained the {@code CUSTOMER} value. {@code ddl-auto=update}
 * never updates an existing CHECK constraint when an enum gains a value, so every insert with
 * {@code source=CUSTOMER} was rejected in production until this runs. Idempotent and safe to
 * leave in permanently — a no-op once the constraint is gone, since valid {@code Source} values
 * are enforced by the Java enum, not the database.
 */
@Component
public class ConversationMessageSchemaFixer {

    private static final Logger log = LoggerFactory.getLogger(ConversationMessageSchemaFixer.class);

    private final JdbcTemplate jdbcTemplate;

    public ConversationMessageSchemaFixer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void dropStaleSourceCheckConstraint() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints " +
                "WHERE table_name = 'conversation_messages' " +
                "AND constraint_name = 'conversation_messages_source_check'",
                Integer.class
            );
            if (count != null && count > 0) {
                jdbcTemplate.execute(
                    "ALTER TABLE conversation_messages DROP CONSTRAINT conversation_messages_source_check"
                );
                log.info("Dropped stale conversation_messages_source_check constraint");
            }
        } catch (Exception ex) {
            log.warn("Could not check/drop conversation_messages_source_check constraint", ex);
        }
    }
}
