package ai.genesisbrands.widget;

import ai.genesisbrands.platform.WidgetDescriptor;
import org.springframework.stereotype.Component;

/**
 * The signed-in client's inbox of standing-agent output, each item approved or rejected with
 * optional feedback the agent reads on its next run. Calls {@code /api/client/standing/inbox}.
 */
@Component
public class AgentInboxWidget implements WidgetDescriptor {

    @Override
    public String widgetType() {
        return "agentInbox";
    }

    @Override
    public String displayName() {
        return "Standing agents: inbox";
    }

    @Override
    public String description() {
        return "What the client's standing agents delivered, waiting for approval.";
    }
}
