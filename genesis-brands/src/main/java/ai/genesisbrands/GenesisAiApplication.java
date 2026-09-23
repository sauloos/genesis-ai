package ai.genesisbrands;

import ai.genesisbrands.controller.ConsultantController;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code ConsultantController} is excluded from component scan because it's registered
 * exclusively via the conditional @Bean in {@code ConsultantServiceConfiguration} — see
 * that class for why (scan-order sensitivity of @ConditionalOnBean on a scanned class).
 */
@SpringBootApplication
@ComponentScan(excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = ConsultantController.class))
@EnableScheduling
@EnableAsync
public class GenesisAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(GenesisAiApplication.class, args);
    }
}
