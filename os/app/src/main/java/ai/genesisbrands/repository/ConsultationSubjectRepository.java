package ai.genesisbrands.repository;

import ai.genesisbrands.model.ConsultationSubject;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsultationSubjectRepository extends JpaRepository<ConsultationSubject, String> {
}
