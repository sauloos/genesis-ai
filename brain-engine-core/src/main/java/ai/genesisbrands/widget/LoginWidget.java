package ai.genesisbrands.widget;

import ai.genesisbrands.platform.WidgetConfigOption;
import ai.genesisbrands.platform.WidgetConfigOption.OptionType;
import ai.genesisbrands.platform.WidgetDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Customer-facing login/signup form (email/password + optional Google Sign-In). Pair
 * with a ContentWidget on the same page for inviting copy — this widget stays
 * minimal, matching QuestionnaireWidget's convention of not duplicating what an
 * adjacent content widget already covers. Core, tenant-usable as-is.
 */
@Component
public class LoginWidget implements WidgetDescriptor {

    @Override
    public String widgetType() {
        return "login";
    }

    @Override
    public String displayName() {
        return "Login / Sign up";
    }

    @Override
    public String description() {
        return "Email/password and Google sign-in. Advances the flow automatically on success.";
    }

    @Override
    public List<WidgetConfigOption> configOptions() {
        return List.of(
            new WidgetConfigOption("googleEnabled", "Enable Google sign-in", OptionType.BOOLEAN, List.of(), "true")
        );
    }
}
