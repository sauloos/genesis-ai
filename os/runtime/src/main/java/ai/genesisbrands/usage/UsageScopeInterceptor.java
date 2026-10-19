package ai.genesisbrands.usage;

import ai.genesisbrands.model.ClientUser;
import ai.genesisbrands.security.ClientAuthFilter;
import ai.genesisbrands.security.ClientAuthHelper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.function.Supplier;

/**
 * Opens a {@link UsageScope} for each API request: the client is whoever a connector key or the
 * client session cookie names, and the source is the request path. The session lookup is
 * deferred until a model call is recorded, so requests that never call a model cost nothing.
 */
@Configuration
@RequiredArgsConstructor
public class UsageScopeInterceptor implements AsyncHandlerInterceptor, WebMvcConfigurer {

    private static final String PREVIOUS = UsageScopeInterceptor.class.getName() + ".previous";

    private final ClientAuthHelper clientAuth;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**", "/mcp/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        req.setAttribute(PREVIOUS, UsageContext.current());
        UsageContext.set(new UsageScope(clientOf(req), null, req.getRequestURI(), null));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
        UsageContext.restore((UsageScope) req.getAttribute(PREVIOUS));
    }

    // A streaming response hands off to another thread; free this one now (afterCompletion
    // only runs on the final async dispatch).
    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest req, HttpServletResponse res, Object handler) {
        UsageContext.restore((UsageScope) req.getAttribute(PREVIOUS));
    }

    private Supplier<String> clientOf(HttpServletRequest req) {
        Object resolved = req.getAttribute(UsageScope.CLIENT_USER_ATTRIBUTE);
        if (resolved instanceof String id) return () -> id;
        if (ClientAuthFilter.extractSessionCookie(req) == null) return null;
        return new Supplier<>() {
            private String id;
            private boolean done;

            @Override
            public synchronized String get() {
                if (!done) {
                    id = clientAuth.resolve(req).map(ClientUser::getId).orElse(null);
                    done = true;
                }
                return id;
            }
        };
    }
}
