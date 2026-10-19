package ai.genesisbrands.agent.clientpulse;

import ai.genesisbrands.platform.AgentCustomOption;
import ai.genesisbrands.platform.AgentCustomOption.OptionType;
import ai.genesisbrands.platform.CoreAgent;
import ai.genesisbrands.platform.standing.Cadence;
import ai.genesisbrands.platform.standing.StandingAgent;
import ai.genesisbrands.platform.standing.StandingRunContext;
import ai.genesisbrands.platform.standing.StandingRunResult;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GenesisOS standing agent: a periodic read on the client's account (brand, deliverables,
 * decisions waiting) delivered to the client's inbox as a short report. Generic across tenants:
 * everything it knows comes from the run's client context, which the standing runtime builds
 * from the tenant's own SPIs. It has no Playground or Live view; clients reach it through the
 * agentSubscriptions widget.
 */
@Component
public class ClientPulseAgent implements CoreAgent, StandingAgent {

    static final String ID = "client-pulse";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneOffset.UTC);

    @Override
    public String agentId() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Client Pulse";
    }

    @Override
    public String description() {
        return "A regular read on your account: what moved, what is waiting on you, and what to do next.";
    }

    @Override
    public String testEndpoint() {
        return "/api/client/standing/offers";
    }

    @Override
    public boolean supportsPlayground() {
        return false;
    }

    @Override
    public boolean requiresEngagementContext() {
        return false;
    }

    @Override
    public Set<Cadence> allowedCadences() {
        return EnumSet.of(Cadence.WEEKLY, Cadence.FORTNIGHTLY, Cadence.MONTHLY);
    }

    @Override
    public List<AgentCustomOption> subscriptionOptions() {
        return List.of(
            new AgentCustomOption("focus", "Focus on", OptionType.MULTI_SELECT,
                List.of("Progress", "Decisions waiting", "Next steps", "Ideas"), "Progress,Decisions waiting,Next steps",
                "What the report covers."),
            new AgentCustomOption("length", "Length", OptionType.SELECT,
                List.of("Brief", "Detailed"), "Brief", null),
            new AgentCustomOption("language", "Language", OptionType.SELECT,
                List.of("English", "Portuguese", "Spanish", "French", "German"), "English", null),
            new AgentCustomOption("notes", "Anything to keep in mind", OptionType.TEXT, List.of(), "",
                "For example a launch date, or what matters most this quarter."));
    }

    @Override
    public StandingRunResult run(StandingRunContext ctx) {
        String reply = ctx.model().complete(prompt(ctx));
        Report report = Report.parse(reply);
        String title = report.title() != null ? report.title() : "Your account on " + DAY.format(ctx.now());
        return StandingRunResult.of(new StandingRunResult.Item(StandingRunResult.Item.REPORT, title, report.summary(),
            report.body(), Map.of("periodStart", ctx.previousRunAt() == null ? "" : ctx.previousRunAt().toString())));
    }

    static String prompt(StandingRunContext ctx) {
        StringBuilder p = new StringBuilder();
        p.append("Client: ").append(ctx.clientName()).append("\n");
        p.append("Today: ").append(DAY.format(ctx.now())).append("\n");
        p.append("Previous report: ").append(ctx.previousRunAt() == null ? "none, this is the first" : DAY.format(ctx.previousRunAt())).append("\n\n");
        p.append("Cover: ").append(opt(ctx, "focus").isBlank() ? "Progress, Decisions waiting, Next steps" : opt(ctx, "focus").replace(",", ", ")).append("\n");
        p.append("Length: ").append(opt(ctx, "length").isBlank() ? "Brief" : opt(ctx, "length")).append("\n");
        p.append("Write in: ").append(opt(ctx, "language").isBlank() ? "English" : opt(ctx, "language")).append("\n");
        if (!opt(ctx, "notes").isBlank()) p.append("The client asked you to keep in mind: ").append(opt(ctx, "notes")).append("\n");
        if (!ctx.recentDecisions().isEmpty()) {
            p.append("\nHow the client responded to your earlier reports (follow their feedback):\n");
            for (StandingRunContext.Decision d : ctx.recentDecisions()) {
                p.append("- \"").append(d.title()).append("\": ").append(d.status().toLowerCase());
                if (d.feedback() != null && !d.feedback().isBlank()) p.append(" — \"").append(d.feedback().trim()).append("\"");
                p.append("\n");
            }
        }
        p.append("\n<client_context>\n")
            .append(ctx.clientContext() == null || ctx.clientContext().isBlank() ? "(nothing on record yet)" : ctx.clientContext())
            .append("\n</client_context>\n");
        return p.toString();
    }

    private static String opt(StandingRunContext ctx, String key) {
        String value = ctx.option(key);
        return value == null ? "" : value.trim();
    }

    /** The model's reply, split into the TITLE / SUMMARY lines the prompt asks for and the body. */
    record Report(String title, String summary, String body) {

        static Report parse(String reply) {
            String title = null, summary = null;
            StringBuilder body = new StringBuilder();
            boolean inBody = false;
            for (String line : (reply == null ? "" : reply.strip()).split("\n", -1)) {
                if (!inBody && title == null && line.startsWith("TITLE:")) {
                    title = line.substring(6).strip();
                } else if (!inBody && summary == null && line.startsWith("SUMMARY:")) {
                    summary = line.substring(8).strip();
                } else if (!inBody && line.isBlank()) {
                    // blank lines between the header lines and the body
                } else {
                    inBody = true;
                    body.append(line).append("\n");
                }
            }
            return new Report(blankToNull(title), blankToNull(summary), body.toString().strip());
        }

        private static String blankToNull(String s) {
            return s == null || s.isBlank() ? null : s;
        }
    }
}
