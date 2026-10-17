package ai.genesisbrands.repository;

import ai.genesisbrands.model.ConversationMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, String> {

    List<ConversationMessage> findBySubjectIdOrderByCreatedAtAsc(String subjectId);

    List<ConversationMessage> findBySubjectIdAndSourceOrderByCreatedAtAsc(
        String subjectId, ConversationMessage.Source source);

    // Rows saved before the `source` column existed have a null source and are real
    // client-facing consultant history, so they belong here, not in PLAYGROUND's history.
    @Query("SELECT m FROM ConversationMessage m WHERE m.subjectId = :subjectId "
        + "AND (m.source IS NULL OR m.source = ai.genesisbrands.model.ConversationMessage.Source.CONSULTANT) "
        + "ORDER BY m.createdAt ASC")
    List<ConversationMessage> findConsultantHistoryBySubjectId(@Param("subjectId") String subjectId);

    // Backs the Playground subject picker: only subjects that already have a
    // playground-tagged message should be listed there — never the full, real
    // client-facing subject/brand roster (see ConsultantService#listSubjects).
    @Query("SELECT DISTINCT m.subjectId FROM ConversationMessage m WHERE m.source = :source")
    List<String> findDistinctSubjectIdsBySource(@Param("source") ConversationMessage.Source source);

    // Backs the Dashboard/Consultant subject picker's exclusion of playground-only test
    // subjects: a subject counts as "real" if it has never been chatted with (no rows at
    // all — still a legitimate brand to start a first conversation with) or if it has at
    // least one real (non-playground) message. Legacy null-source rows count as real, same
    // as findConsultantHistoryBySubjectId.
    @Query("SELECT DISTINCT m.subjectId FROM ConversationMessage m "
        + "WHERE m.source IS NULL OR m.source = ai.genesisbrands.model.ConversationMessage.Source.CONSULTANT")
    List<String> findDistinctSubjectIdsWithConsultantActivity();
}
