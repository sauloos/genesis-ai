package ai.genesisbrands.usage;

import org.springframework.core.task.TaskDecorator;
import org.springframework.stereotype.Component;

/**
 * Carries the submitting thread's {@link UsageScope} into {@code @Async} work, so a pipeline a
 * client starts is still billed to that client. Boot applies a single TaskDecorator bean to its
 * auto-configured executor.
 */
@Component
public class UsageTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        UsageScope scope = UsageContext.current();
        if (scope == null) return runnable;
        // The request that submitted this work ends long before the work does, so pin the client now.
        UsageScope pinned = scope.pinned();
        return () -> UsageContext.runWith(pinned, runnable);
    }
}
