package ai.genesisbrands.repository;

import ai.genesisbrands.model.QuestionnaireQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QuestionnaireQuestionRepository extends JpaRepository<QuestionnaireQuestion, String> {
    List<QuestionnaireQuestion> findByQuestionnaireIdOrderByOrderIndexAsc(String questionnaireId);
    void deleteByQuestionnaireId(String questionnaireId);
}
