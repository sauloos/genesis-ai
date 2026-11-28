package ai.genesisbrands.previews;

import ai.genesisbrands.security.ClientAuthHelper;
import ai.genesisbrands.security.SignedTokens;
import ai.genesisbrands.service.BlobStorageService;
import ai.genesisbrands.service.DocumentPreviewProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;

/**
 * Switches previews on only when the app has a {@link DocumentPreviewProvider}, the admin has
 * set {@code genesis.previews.enabled=true}, and (to serve anything) a signing key exists.
 * Off by default, so adopting the module changes nothing until previews are deliberately turned on.
 */
@Configuration
public class PreviewsConfiguration {

    @Bean
    @ConditionalOnBean(DocumentPreviewProvider.class)
    @ConditionalOnProperty(name = "genesis.previews.enabled", havingValue = "true")
    public DocumentPreviewService documentPreviewService(
        DocumentPreviewProvider provider, BlobStorageService blobs, PreviewViewLogRepository audit,
        ObjectMapper mapper,
        @Value("${genesis.previews.signing-key:}") String signingKey,
        @Value("${genesis.previews.token-ttl-seconds:300}") int ttlSeconds,
        @Value("${genesis.previews.manifests-per-minute:10}") int manifestsPerMinute,
        @Value("${genesis.previews.pages-per-minute:90}") int pagesPerMinute,
        @Value("${genesis.previews.pages-per-day:1500}") int pagesPerDay,
        @Value("${genesis.previews.pages-per-ip-per-minute:240}") int pagesPerIpPerMinute,
        @Value("${genesis.previews.max-width:1200}") int maxWidth
    ) {
        return new DocumentPreviewService(provider, blobs, new SignedTokens(signingKey), audit, LIMITER, mapper,
            new DocumentPreviewService.Settings(Duration.ofSeconds(ttlSeconds), manifestsPerMinute, pagesPerMinute,
                pagesPerDay, pagesPerIpPerMinute, maxWidth), Clock.systemUTC());
    }

    @Bean
    @ConditionalOnBean(DocumentPreviewService.class)
    public PreviewController previewController(DocumentPreviewService service, ClientAuthHelper clientAuth) {
        return new PreviewController(service, clientAuth);
    }

    // One limiter per JVM, shared by the service and its housekeeping.
    private static final PreviewRateLimiter LIMITER = new PreviewRateLimiter();

    @Scheduled(fixedDelay = 600_000)
    void sweepLimiter() {
        LIMITER.sweep(Duration.ofDays(1));
    }
}
