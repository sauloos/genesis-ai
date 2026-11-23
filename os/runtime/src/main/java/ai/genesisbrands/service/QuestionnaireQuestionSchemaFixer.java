package ai.genesisbrands.service;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Relaxes the database-level constraint on {@code questionnaire_questions.type} so the column
 * holds whatever {@code QuestionnaireQuestion.Type} currently names.
 *
 * <p>{@code @Enumerated(STRING)} is only schema-safe in Java: Hibernate pins the values it knew
 * at table-creation time into the schema — a CHECK constraint on Postgres, a native
 * {@code ENUM(...)} column type on H2 — and {@code ddl-auto=update} never revises either when the
 * Java enum gains a value. The nine answer types added for the Purpose Playbook questionnaire
 * (CHIP_PICK, RANKING, ICON_GRID, IMAGE_CHOICE, PALETTE_CHOICE, TYPE_SPECIMEN, URL, FILE_UPLOAD,
 * SYNTHESIS) are therefore rejected on insert by any database created before them, with a 500 on
 * the authoring API and nothing wrong in the code.
 *
 * <p>Unlike {@link ConversationMessageSchemaFixer}, which widened its H2 enum to a new fixed list,
 * this drops the pinning altogether — plain {@code varchar} — so the next answer type needs no
 * migration. The Java enum is the only validator the values ever needed. Idempotent, and a no-op
 * once the column is unconstrained.
 */
@Component
public class QuestionnaireQuestionSchemaFixer {

    private static final Logger log = LoggerFactory.getLogger(QuestionnaireQuestionSchemaFixer.class);

    private final JdbcTemplate jdbcTemplate;

    /**
     * The factory is injected only for ordering: Hibernate exports its schema while the factory is
     * built, so taking it as a dependency guarantees the table exists before this looks at it.
     */
    public QuestionnaireQuestionSchemaFixer(JdbcTemplate jdbcTemplate, EntityManagerFactory factory) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void relaxTypeColumn() {
        dropStalePostgresCheckConstraint();
        unpinStaleH2EnumColumn();
    }

    private void dropStalePostgresCheckConstraint() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints " +
                "WHERE table_name = 'questionnaire_questions' " +
                "AND constraint_name = 'questionnaire_questions_type_check'",
                Integer.class
            );
            if (count != null && count > 0) {
                jdbcTemplate.execute(
                    "ALTER TABLE questionnaire_questions DROP CONSTRAINT questionnaire_questions_type_check"
                );
                log.info("Dropped stale questionnaire_questions_type_check constraint");
            }
        } catch (Exception ex) {
            log.debug("Could not check/drop questionnaire_questions_type_check (likely not Postgres)", ex);
        }
    }

    private void unpinStaleH2EnumColumn() {
        try {
            String dataType = jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns " +
                "WHERE table_name = 'QUESTIONNAIRE_QUESTIONS' AND column_name = 'TYPE'",
                String.class
            );
            if ("ENUM".equals(dataType)) {
                jdbcTemplate.execute(
                    "ALTER TABLE QUESTIONNAIRE_QUESTIONS ALTER COLUMN \"TYPE\" " +
                    "SET DATA TYPE VARCHAR(40)"
                );
                log.info("Unpinned QUESTIONNAIRE_QUESTIONS.TYPE from a fixed enum list to varchar");
            }
        } catch (Exception ex) {
            log.debug("Could not check/unpin QUESTIONNAIRE_QUESTIONS.TYPE (likely not H2)", ex);
        }
    }
}
