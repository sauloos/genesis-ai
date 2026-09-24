package ai.genesisbrands.service;

import ai.genesisbrands.model.ConsultationSubject;
import ai.genesisbrands.repository.ConsultationSubjectRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * GenesisOS' {@link ConsultantSubjectProvider}: a consultant conversation is scoped to a
 * {@link ConsultationSubject} (a named thread, not a brand — see its javadoc). Its
 * presence as a bean is what turns on ConsultantService (see ConsultantServiceConfiguration
 * in brain-engine-core), mirroring genesis-brands' BrandConsultantSubjectProvider.
 */
@Component
public class PlatformConsultantSubjectProvider implements ConsultantSubjectProvider {

    private final ConsultationSubjectRepository subjectRepo;

    public PlatformConsultantSubjectProvider(ConsultationSubjectRepository subjectRepo) {
        this.subjectRepo = subjectRepo;
    }

    @Override
    public ConsultantSubject find(String subjectId) {
        ConsultationSubject subject = subjectRepo.findById(subjectId)
            .orElseThrow(() -> new IllegalArgumentException("Subject not found: " + subjectId));
        return new ConsultantSubject(subject.getId(), subject.getName(), subject.getIndustry(), subject.getAudience(), subject.getBrief());
    }

    @Override
    public List<ConsultantSubjectSummary> list() {
        return subjectRepo.findAll().stream()
            .map(s -> new ConsultantSubjectSummary(s.getId(), s.getName(), s.getUpdatedAt()))
            .toList();
    }

    @Override
    public ConsultantSubjectSummary create(String name, String industry, String audience, String brief) {
        ConsultationSubject subject = new ConsultationSubject();
        subject.setId(UUID.randomUUID().toString());
        subject.setName(name);
        subject.setIndustry(industry);
        subject.setAudience(audience);
        subject.setBrief(brief);
        subject = subjectRepo.save(subject);
        return new ConsultantSubjectSummary(subject.getId(), subject.getName(), subject.getUpdatedAt());
    }

    @Override
    public void delete(String subjectId) {
        subjectRepo.deleteById(subjectId);
    }
}
