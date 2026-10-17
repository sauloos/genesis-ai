package ai.genesisbrands.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.TimeUnit;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Images: long cache — they rarely change and are large (bg-earth.jpg etc.)
        // The global no-store in application.yml is overridden here for image types only.
        registry.addResourceHandler("/*.jpg", "/*.jpeg", "/*.png", "/*.webp", "/*.gif", "/*.svg", "/*.ico")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic());
        // All other static files keep the no-store default from application.yml.
        // No /**  handler here — adding one breaks @RestController routing.
    }
}
