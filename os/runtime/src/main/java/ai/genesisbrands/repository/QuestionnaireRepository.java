package ai.genesisbrands.repository;

import ai.genesisbrands.model.Questionnaire;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QuestionnaireRepository extends JpaRepository<Questionnaire, String> {
    List<Questionnaire> findAllByOrderByUpdatedAtDesc();
    List<Questionnaire> findByStatus(Questionnaire.Status status);
}
