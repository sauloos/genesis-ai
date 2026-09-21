package ai.genesisbrands.config;

import ai.genesisbrands.service.TenantConsultantConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ConsultantConfigConfiguration {

    @Bean
    @ConditionalOnMissingBean(TenantConsultantConfig.class)
    public TenantConsultantConfig configurableTenantConsultantConfig(ConsultantPersonaProperties properties) {
        return new ConfigurableTenantConsultantConfig(properties);
    }
}
