package ai.genesisbrands.widget;

import ai.genesisbrands.platform.WidgetDescriptor;
import org.springframework.stereotype.Component;

/**
 * Static "coming soon" placeholder for the future products catalog on the client
 * dashboard. No config, no server-side logic.
 */
@Component
public class DashboardCatalogWidget implements WidgetDescriptor {

    @Override
    public String widgetType() {
        return "dashboardCatalog";
    }

    @Override
    public String displayName() {
        return "Dashboard: Catalog";
    }

    @Override
    public String description() {
        return "Static \"coming soon\" placeholder for the products catalog.";
    }
}
