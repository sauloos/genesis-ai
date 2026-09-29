package ai.genesisbrands.widget;

import ai.genesisbrands.platform.WidgetDescriptor;
import org.springframework.stereotype.Component;

/**
 * Lists the signed-in visitor's completed engagements and their downloadable assets
 * (brand book PDFs, logo zips), locked until payment. Fetches {@code /api/engagements/mine}
 * client-side; no config needed since it's always scoped to the current session's user.
 */
@Component
public class DashboardAssetsWidget implements WidgetDescriptor {

    @Override
    public String widgetType() {
        return "dashboardAssets";
    }

    @Override
    public String displayName() {
        return "Dashboard: Assets";
    }

    @Override
    public String description() {
        return "The signed-in visitor's completed engagements with PDF/logo downloads, locked until payment.";
    }
}
