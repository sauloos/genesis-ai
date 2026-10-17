package ai.genesisbrands.config;

import ai.genesisbrands.controller.ClientWorkspaceController;
import ai.genesisbrands.security.ClientAuthHelper;
import ai.genesisbrands.service.ClientWorkspaceProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link ClientWorkspaceController} only when a tenant supplies a
 * {@link ClientWorkspaceProvider} bean — a bare platform deployment with no work-item
 * concept simply has no client workspace endpoint. Mirrors
 * {@link ConsultantServiceConfiguration} exactly, including the reason for routing
 * through an explicit @Bean method instead of a class-level @ConditionalOnBean.
 */
@Configuration
public class ClientWorkspaceConfiguration {

    @Bean
    @ConditionalOnBean(ClientWorkspaceProvider.class)
    public ClientWorkspaceController clientWorkspaceController(
        ClientWorkspaceProvider provider,
        ClientAuthHelper clientAuthHelper
    ) {
        return new ClientWorkspaceController(provider, clientAuthHelper);
    }
}
