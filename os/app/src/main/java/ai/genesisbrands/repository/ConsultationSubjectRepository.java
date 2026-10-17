package ai.genesisbrands.repository;

import ai.genesisbrands.model.ConsultationSubject;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConsultationSubjectRepository extends JpaRepository<ConsultationSubject, String> {

    Optional<ConsultationSubject> findFirstByClientUserIdOrderByCreatedAtAsc(String clientUserId);

    List<ConsultationSubject> findAllByClientUserIdIsNull();
}
