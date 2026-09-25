package ai.genesisbrands.config;

import ai.genesisbrands.agent.consultant.ConsultantCoreAgent;
import ai.genesisbrands.controller.ConsultantController;
import ai.genesisbrands.repository.ConversationMessageRepository;
import ai.genesisbrands.service.ConsultantService;
import ai.genesisbrands.service.ConsultantSubjectProvider;
import ai.genesisbrands.service.ContextEnrichmentService;
import ai.genesisbrands.service.Layer1Service;
import ai.genesisbrands.service.RetrievalService;
import ai.genesisbrands.service.TenantConsultantConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link ConsultantService} and {@link ConsultantController} only when a
 * tenant supplies a {@link ConsultantSubjectProvider} bean — a bare platform deployment
 * with no subject concept simply has no consultant capability. Both are wired via
 * explicit {@code @Bean} methods rather than left to component scan: {@code @ConditionalOnBean}
 * on a plain component-scanned class is order-sensitive (it depends on the tenant's
 * {@code ConsultantSubjectProvider} bean definition already being registered when the
 * condition is evaluated), and that ordering isn't guaranteed to match across different
 * classpath/JAR enumeration orders — it was observed to hold in local Gradle bootRun but
 * fail in an ACR-built packaged jar. Routing through this configuration class's @Bean
 * methods keeps registration deterministic, and doubles as the only registration path
 * for {@code ConsultantController} — both {@code GenesisAiApplication} and
 * {@code GenesisOsApplication} explicitly exclude it from component scan so it's never
 * registered unconditionally on a bare platform deployment.
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

    @Bean
    @ConditionalOnBean(ConsultantSubjectProvider.class)
    public ConsultantController consultantController(
        ConsultantService consultantService,
        ConsultantSubjectProvider subjectProvider,
        ContextEnrichmentService enrichment
    ) {
        return new ConsultantController(consultantService, subjectProvider, enrichment);
    }

    @Bean
    @ConditionalOnBean(ConsultantSubjectProvider.class)
    public ConsultantCoreAgent consultantCoreAgent() {
        return new ConsultantCoreAgent();
    }
}
