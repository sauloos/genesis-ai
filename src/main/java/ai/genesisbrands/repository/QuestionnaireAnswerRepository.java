package ai.genesisbrands.repository;

import ai.genesisbrands.model.QuestionnaireAnswer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface QuestionnaireAnswerRepository extends JpaRepository<QuestionnaireAnswer, String> {
    List<QuestionnaireAnswer> findByResponseIdOrderByCreatedAtAsc(String responseId);
    Optional<QuestionnaireAnswer> findByResponseIdAndQuestionId(String responseId, String questionId);
}
