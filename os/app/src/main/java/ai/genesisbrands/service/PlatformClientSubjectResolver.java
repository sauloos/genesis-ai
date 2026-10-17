package ai.genesisbrands.service;

import ai.genesisbrands.model.ClientUser;
import ai.genesisbrands.model.ConsultationSubject;
import ai.genesisbrands.repository.ConsultationSubjectRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * GenesisOS' {@link ClientOwnSubjectResolver}: the platform has no brand or engagement, so a
 * client's "own" subject is simply one {@link ConsultationSubject} per client, created on first
 * use with an empty brief. That is enough to give every logged-in or connector-key client the
 * pure OS Consultant — with its own conversation, grounded only in the deployment's knowledge
 * base — mirroring genesis-brands' EngagementOwnSubjectResolver.
 */
@Component
public class PlatformClientSubjectResolver implements ClientOwnSubjectResolver {

    private final ConsultationSubjectRepository subjectRepo;

    public PlatformClientSubjectResolver(ConsultationSubjectRepository subjectRepo) {
        this.subjectRepo = subjectRepo;
    }

    @Override
    @Transactional
    public Optional<String> resolveOwnSubjectId(ClientUser client) {
        ConsultationSubject subject = subjectRepo.findFirstByClientUserIdOrderByCreatedAtAsc(client.getId())
            .orElseGet(() -> {
                ConsultationSubject created = new ConsultationSubject();
                created.setId(UUID.randomUUID().toString());
                created.setClientUserId(client.getId());
                created.setName(client.getName() != null && !client.getName().isBlank() ? client.getName() : client.getEmail());
                return subjectRepo.save(created);
            });
        return Optional.of(subject.getId());
    }
}
