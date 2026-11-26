package ai.genesisbrands.widget.logoassessment;

import ai.genesisbrands.platform.WidgetConfigOption;
import ai.genesisbrands.platform.WidgetConfigOption.OptionType;
import ai.genesisbrands.platform.WidgetDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Genesis Brands widget for the page after discovery: assesses the logo the client uploaded, shows
 * the score, the verdict and what we would keep and change, and asks what to do with it. The
 * choice reaches the engagement as the {@code look.logoDecision} answer (see
 * {@link LogoAssessmentAnswerSource}). A client with no logo goes straight on.
 */
@Component
public class LogoAssessmentWidget implements WidgetDescriptor {

    static final String TYPE = "logoAssessment";

    @Override
    public String widgetType() {
        return TYPE;
    }

    @Override
    public String displayName() {
        return "Logo assessment";
    }

    @Override
    public String description() {
        return "Scores the logo the client uploaded in discovery and asks whether to keep, evolve or replace it.";
    }

    @Override
    public List<WidgetConfigOption> configOptions() {
        return List.of(
            new WidgetConfigOption("heading", "Heading", OptionType.STRING, List.of(), "What we see in your logo"),
            new WidgetConfigOption("intro", "Intro", OptionType.STRING, List.of(),
                "Before we design anything, here is our honest read of the logo you have today.")
        );
    }
}
