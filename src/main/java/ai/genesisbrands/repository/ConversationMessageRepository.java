package ai.genesisbrands.repository;

import ai.genesisbrands.model.ConversationMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, String> {

    List<ConversationMessage> findByBrandIdOrderByCreatedAtAsc(String brandId);
}
