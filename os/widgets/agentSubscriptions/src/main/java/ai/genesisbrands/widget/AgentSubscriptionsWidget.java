package ai.genesisbrands.widget;

import ai.genesisbrands.platform.WidgetDescriptor;
import org.springframework.stereotype.Component;

/**
 * The signed-in client's standing agents: the agents an admin offers, and for each one the
 * client's own frequency, options, pause/resume and run-now. Calls {@code /api/client/standing};
 * renders nothing useful in an app that does not adopt os/standing.
 */
@Component
public class AgentSubscriptionsWidget implements WidgetDescriptor {

    @Override
    public String widgetType() {
        return "agentSubscriptions";
    }

    @Override
    public String displayName() {
        return "Standing agents: subscriptions";
    }

    @Override
    public String description() {
        return "Lets a signed-in client subscribe to agents that keep working on a schedule, and choose how often and how.";
    }
}
