package ai.genesisbrands.widget;

import ai.genesisbrands.platform.WidgetConfigOption;
import ai.genesisbrands.platform.WidgetConfigOption.OptionType;
import ai.genesisbrands.platform.WidgetDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * A right-aligned "Sign out" button for a signed-in visitor. Renders nothing if no
 * ClientUser session is active. Place first (lowest orderInSlot) in a page's slot so
 * it sits above the page's other widgets — it doesn't participate in flow navigation,
 * so it needs no PageTransition wired to its (unused) default "next" outcome.
 */
@Component
public class LogoutWidget implements WidgetDescriptor {

    @Override
    public String widgetType() {
        return "logout";
    }

    @Override
    public String displayName() {
        return "Logout";
    }

    @Override
    public String description() {
        return "Right-aligned sign-out button for a signed-in visitor. Invisible if not signed in.";
    }

    @Override
    public List<WidgetConfigOption> configOptions() {
        return List.of(
            new WidgetConfigOption("label", "Button label", OptionType.STRING, List.of(), "Sign out")
        );
    }
}
