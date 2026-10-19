package ai.genesisbrands.standing;

import ai.genesisbrands.model.ClientUser;
import ai.genesisbrands.platform.standing.StandingContextContributor;
import ai.genesisbrands.repository.ClientUserRepository;
import ai.genesisbrands.service.ClientOwnSubjectResolver;
import ai.genesisbrands.service.ClientWorkItem;
import ai.genesisbrands.service.ClientWorkspaceProvider;
import ai.genesisbrands.service.ConsultantSubject;
import ai.genesisbrands.service.ConsultantSubjectProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Builds the client context a standing run receives, from the database, for the run's client
 * only. The generic sections reuse the SPIs a tenant already implements for the customer-aware
 * Consultant and the dashboard (own subject, workspace), so a tenant gets brand-grounded
 * standing runs with no standing-specific code; {@link StandingContextContributor} beans add
 * anything else.
 */
@Slf4j
@Service
public class StandingContextService {

    static final int MAX_CHARS = 30_000;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final ClientUserRepository clientUsers;
    private final ObjectProvider<ClientOwnSubjectResolver> subjectResolver;
    private final ObjectProvider<ConsultantSubjectProvider> subjects;
    private final ObjectProvider<ClientWorkspaceProvider> workspace;
    private final List<StandingContextContributor> contributors;
    private final InboxItemRepository inbox;
    private final StandingCatalog catalog;

    public StandingContextService(ClientUserRepository clientUsers,
                                  ObjectProvider<ClientOwnSubjectResolver> subjectResolver,
                                  ObjectProvider<ConsultantSubjectProvider> subjects,
                                  ObjectProvider<ClientWorkspaceProvider> workspace,
                                  List<StandingContextContributor> contributors,
                                  InboxItemRepository inbox,
                                  StandingCatalog catalog) {
        this.clientUsers = clientUsers;
        this.subjectResolver = subjectResolver;
        this.subjects = subjects;
        this.workspace = workspace;
        this.contributors = contributors.stream().sorted(Comparator.comparingInt(StandingContextContributor::order)).toList();
        this.inbox = inbox;
        this.catalog = catalog;
    }

    public String build(ClientUser client, String agentId) {
        StringBuilder out = new StringBuilder();
        append(out, safely("subject", () -> subjectSection(client)));
        append(out, safely("workspace", () -> workspaceSection(client.getId())));
        append(out, safely("inbox", () -> inboxSection(client.getId())));
        for (StandingContextContributor c : contributors) {
            append(out, safely(c.getClass().getSimpleName(), () -> c.section(client.getId(), agentId)));
        }
        return out.length() > MAX_CHARS ? out.substring(0, MAX_CHARS) + "\n\n(context truncated)" : out.toString();
    }

    public Optional<ClientUser> client(String clientUserId) {
        return clientUsers.findById(clientUserId);
    }

    private String subjectSection(ClientUser client) {
        ClientOwnSubjectResolver resolver = subjectResolver.getIfAvailable();
        ConsultantSubjectProvider provider = subjects.getIfAvailable();
        if (resolver == null || provider == null) return null;
        Optional<String> subjectId = resolver.resolveOwnSubjectId(client);
        if (subjectId.isEmpty()) return null;
        ConsultantSubject s = provider.find(subjectId.get());
        StringBuilder sb = new StringBuilder("## The client's brand\n");
        line(sb, "Name", s.name());
        line(sb, "Industry", s.industry());
        line(sb, "Audience", s.audience());
        if (s.brief() != null && !s.brief().isBlank()) sb.append("\n").append(s.brief().trim()).append("\n");
        return sb.toString();
    }

    private String workspaceSection(String clientUserId) {
        ClientWorkspaceProvider provider = workspace.getIfAvailable();
        if (provider == null) return null;
        List<ClientWorkItem> items = provider.listMine(clientUserId);
        if (items.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("## Deliverables\n");
        for (ClientWorkItem item : items) {
            sb.append("- ").append(item.title() == null ? "Untitled" : item.title())
                .append(item.inProgress() ? " (in progress)" : "")
                .append(item.unlocked() ? "" : " (locked until payment)");
            if (item.statusNote() != null) sb.append(" — ").append(item.statusNote());
            sb.append("\n");
            for (ClientWorkItem.Variant v : item.variants() == null ? List.<ClientWorkItem.Variant>of() : item.variants()) {
                String assets = v.assets() == null ? "" : String.join(", ", v.assets().stream()
                    .map(a -> a.label() + (a.ready() ? "" : " (not ready)")).toList());
                int versions = v.versions() == null ? 0 : v.versions().size();
                sb.append("  - ").append(v.key()).append(v.chosen() ? " (chosen)" : "")
                    .append(assets.isEmpty() ? "" : ": " + assets)
                    .append(versions > 0 ? "; " + versions + " earlier version(s)" : "").append("\n");
            }
        }
        return sb.toString();
    }

    private String inboxSection(String clientUserId) {
        long pending = inbox.countByClientUserIdAndStatus(clientUserId, InboxItem.PENDING);
        List<InboxItem> recent = inbox.findByClientUserIdOrderByCreatedAtDesc(clientUserId, PageRequest.of(0, 10));
        if (recent.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("## Inbox activity\n");
        sb.append(pending).append(" item(s) waiting for the client's decision.\n");
        for (InboxItem i : recent) {
            sb.append("- ").append(DAY.format(i.getCreatedAt())).append(" · ")
                .append(catalog.displayName(i.getAgentId())).append(" · ").append(i.getTitle())
                .append(" — ").append(i.getStatus().toLowerCase());
            if (i.getFeedback() != null && !i.getFeedback().isBlank()) sb.append(" (\"").append(i.getFeedback().trim()).append("\")");
            sb.append("\n");
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) sb.append("- ").append(label).append(": ").append(value.trim()).append("\n");
    }

    private static void append(StringBuilder out, String section) {
        if (section == null || section.isBlank()) return;
        if (!out.isEmpty()) out.append("\n");
        out.append(section.trim()).append("\n");
    }

    /** One broken section never fails the run; the agent works with what is left. */
    private static String safely(String name, java.util.function.Supplier<String> section) {
        try {
            return section.get();
        } catch (RuntimeException e) {
            log.warn("Standing context section '{}' failed: {}", name, e.getMessage());
            return null;
        }
    }
}
