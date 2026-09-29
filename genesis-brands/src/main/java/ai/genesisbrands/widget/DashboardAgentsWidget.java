package ai.genesisbrands.widget;

import ai.genesisbrands.platform.WidgetDescriptor;
import org.springframework.stereotype.Component;

/**
 * "Coming soon" cards for whichever specialist agents are flagged availableForLiveView
 * in the Agents admin page, shown on the client dashboard's sidebar. No config — fetches
 * the live-filtered list client-side via GET /api/agents/live-view.
 */
@Component
public class DashboardAgentsWidget implements WidgetDescriptor {

    @Override
    public String widgetType() {
        return "dashboardAgents";
    }

    @Override
    public String displayName() {
        return "Dashboard: Agents";
    }

    @Override
    public String description() {
        return "Cards for the specialist agents an admin has enabled for live view (Agents admin page), each marked \"coming soon\".";
    }
}
