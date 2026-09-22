package ai.genesisbrands.config;

import ai.genesisbrands.repository.ConversationMessageRepository;
import ai.genesisbrands.service.ConsultantService;
import ai.genesisbrands.service.ConsultantSubjectProvider;
import ai.genesisbrands.service.Layer1Service;
import ai.genesisbrands.service.RetrievalService;
import ai.genesisbrands.service.TenantConsultantConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link ConsultantService} only when a tenant supplies a {@link ConsultantSubjectProvider}
 * bean — a bare platform deployment with no subject concept simply has no consultant capability,
 * and nothing else in {@code brain-engine-core} depends on this bean existing.
 */
@Configuration
public class ConsultantServiceConfiguration {

    @Bean
    @ConditionalOnBean(ConsultantSubjectProvider.class)
    public ConsultantService consultantService(
        ChatModel chatModel,
        RetrievalService retrieval,
        Layer1Service layer1,
        ConsultantSubjectProvider subjectProvider,
        ConversationMessageRepository messageRepo,
        TenantConsultantConfig tenantConfig
    ) {
        return new ConsultantService(chatModel, retrieval, layer1, subjectProvider, messageRepo, tenantConfig);
    }
}
