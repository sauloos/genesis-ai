package ai.genesisbrands.repository;

import ai.genesisbrands.model.QuestionnaireResponse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QuestionnaireResponseRepository extends JpaRepository<QuestionnaireResponse, String> {
    List<QuestionnaireResponse> findByQuestionnaireIdOrderByCreatedAtDesc(String questionnaireId);
}
