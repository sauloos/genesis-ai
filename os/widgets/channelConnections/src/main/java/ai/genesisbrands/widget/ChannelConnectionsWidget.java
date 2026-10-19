package ai.genesisbrands.widget;

import ai.genesisbrands.platform.WidgetDescriptor;
import org.springframework.stereotype.Component;

/**
 * The signed-in client's social accounts (Facebook Pages, Instagram, LinkedIn): connect,
 * see their state, disconnect. Calls {@code /api/client/channels}; approved inbox posts are
 * published to the accounts connected here.
 */
@Component
public class ChannelConnectionsWidget implements WidgetDescriptor {

    @Override
    public String widgetType() {
        return "channelConnections";
    }

    @Override
    public String displayName() {
        return "Social accounts";
    }

    @Override
    public String description() {
        return "Lets the client connect the social accounts their approved posts are published to.";
    }
}
