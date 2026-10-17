package ai.genesisbrands;

import ai.genesisbrands.platform.RegisteredByConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Classes marked {@link RegisteredByConfiguration} (e.g. {@code ConsultantController},
 * {@code ClientWorkspaceController}) are excluded from component scan because they're
 * registered exclusively via a conditional @Bean in their module's configuration — see
 * {@code ConsultantServiceConfiguration} for why (scan-order sensitivity of
 * @ConditionalOnBean on a scanned class).
 */
@SpringBootApplication
@ComponentScan(excludeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION,
    classes = RegisteredByConfiguration.class))
@EnableScheduling
@EnableAsync
public class GenesisOsApplication {

    public static void main(String[] args) {
        SpringApplication.run(GenesisOsApplication.class, args);
    }
}
