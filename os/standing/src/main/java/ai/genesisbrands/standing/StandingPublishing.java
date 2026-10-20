package ai.genesisbrands.standing;

import ai.genesisbrands.connector.auth.ConnectorPrincipal;
import ai.genesisbrands.connector.mcp.ClientToolContributor;
import ai.genesisbrands.connector.mcp.McpTool;
import ai.genesisbrands.model.ClientUser;
import ai.genesisbrands.platform.channel.PublishChannel.PostMetrics;
import ai.genesisbrands.platform.standing.PostPerformance;
import ai.genesisbrands.publishing.PublishingService;
import ai.genesisbrands.security.ClientAuthHelper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.genesisbrands.connector.mcp.McpTool.integer;
import static ai.genesisbrands.connector.mcp.McpTool.schema;
import static ai.genesisbrands.connector.mcp.McpTool.string;
import static ai.genesisbrands.connector.mcp.McpToolsets.optionalText;
import static ai.genesisbrands.connector.mcp.McpToolsets.text;

/**
 * Publishing an approved inbox post to the client's connected social accounts, when the app
 * adopts os/publishing. Each channel version of the post (its "variant") goes to the accounts
 * of the matching network, now or at a chosen time. The inbox item itself stays APPROVED: what
 * was scheduled or published lives in the publishing runtime's jobs, keyed by the item.
 */
public final class StandingPublishing {

    private StandingPublishing() {}

    static final String PUBLISHING = "ai.genesisbrands.publishing.PublishingService";
    static final String CONNECTORS = "ai.genesisbrands.connector.mcp.ClientToolContributor";

    static String sourceRef(String itemId) {
        return "standing-inbox:" + itemId;
    }

    /** One channel version of the post, with the client's connected accounts on that network. */
    public record VariantTarget(int index, String channel, String label, String text,
                                List<PublishingService.AccountView> accounts) {}

    public record PublishOptions(boolean ready, boolean approved, String proposedFor, int images,
                                 List<VariantTarget> variants, List<PublishingService.JobView> jobs) {}

    public record Target(int variant, String accountId) {}

    /** {@code at} is an ISO instant; missing or past means now. */
    public record PublishRequest(List<Target> targets, String at) {}

    @Component
    @ConditionalOnClass(name = PUBLISHING)
    @RequiredArgsConstructor
    public static class Service {

        private final StandingService standing;
        private final StandingAttachments attachments;
        private final PublishingService publishing;
        private final ObjectMapper objectMapper;

        public PublishOptions options(String clientUserId, String itemId) {
            InboxItem item = standing.ownItem(clientUserId, itemId);
            List<Map<String, Object>> variants = standing.variants(item);
            List<VariantTarget> targets = new ArrayList<>();
            for (int i = 0; i < variants.size(); i++) {
                Map<String, Object> v = variants.get(i);
                String channel = network(v);
                targets.add(new VariantTarget(i, channel, String.valueOf(v.getOrDefault("label", channel)), postText(v),
                    publishing.ready() ? publishing.accountsFor(clientUserId, channel) : List.of()));
            }
            String proposedFor = payloadValue(item, "proposedFor");
            return new PublishOptions(publishing.ready(), InboxItem.APPROVED.equals(item.getStatus()),
                proposedFor, images(item).size(), targets,
                publishing.jobs(clientUserId, sourceRef(itemId)));
        }

        public List<PublishingService.JobView> publish(String clientUserId, String itemId, PublishRequest request) {
            InboxItem item = standing.ownItem(clientUserId, itemId);
            if (!InboxItem.APPROVED.equals(item.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Approve the post before publishing it.");
            }
            if (request == null || request.targets() == null || request.targets().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose at least one account.");
            }
            Instant at = parseAt(request.at());
            List<Map<String, Object>> variants = standing.variants(item);
            List<byte[]> images = readImages(item);
            List<PublishingService.JobView> scheduled = new ArrayList<>();
            for (Target t : request.targets()) {
                if (t.variant() < 0 || t.variant() >= variants.size()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such version of this post.");
                }
                Map<String, Object> v = variants.get(t.variant());
                String network = network(v);
                boolean matches = publishing.accountsFor(clientUserId, network).stream()
                    .anyMatch(a -> a.id().equals(t.accountId()));
                if (!matches) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "That account is not a connected " + v.getOrDefault("label", network) + " account.");
                }
                scheduled.add(publishing.schedule(clientUserId, sourceRef(itemId), t.accountId(), postText(v), images, at));
            }
            return scheduled;
        }

        public PublishingService.JobView cancel(String clientUserId, String itemId, String jobId) {
            standing.ownItem(clientUserId, itemId);
            boolean ofThisItem = publishing.jobs(clientUserId, sourceRef(itemId)).stream().anyMatch(j -> j.id().equals(jobId));
            if (!ofThisItem) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such post");
            return publishing.cancel(clientUserId, jobId);
        }

        private List<StandingAttachments.Stored> images(InboxItem item) {
            return attachments.list(item).stream()
                .filter(f -> f.contentType() != null && f.contentType().startsWith("image/"))
                .toList();
        }

        private List<byte[]> readImages(InboxItem item) {
            List<byte[]> out = new ArrayList<>();
            for (StandingAttachments.Stored f : images(item)) {
                try {
                    out.add(attachments.read(f));
                } catch (IOException e) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "An image of this post is no longer available.");
                }
            }
            return out;
        }

        private String payloadValue(InboxItem item, String key) {
            if (item.getPayloadJson() == null) return null;
            try {
                JsonNode node = objectMapper.readTree(item.getPayloadJson()).get(key);
                return node == null || node.isNull() ? null : node.asText();
            } catch (Exception e) {
                return null;
            }
        }

        private static String network(Map<String, Object> v) {
            return String.valueOf(v.getOrDefault("channel", "")).trim().toLowerCase();
        }
    }

    /**
     * {@link PostPerformance} over the publishing runtime's jobs: each published job that came
     * from an inbox item of the asking agent, with the item's payload and the job's figures.
     */
    @Component
    @ConditionalOnClass(name = PUBLISHING)
    @RequiredArgsConstructor
    public static class Performance implements PostPerformance {

        private final PublishingService publishing;
        private final InboxItemRepository items;
        private final ObjectMapper objectMapper;

        @Override
        public List<PublishedPost> published(String clientUserId, String agentId, Instant since) {
            if (clientUserId == null || agentId == null) return List.of();
            String prefix = sourceRef("");
            List<PublishingService.JobView> jobs = publishing.publishedSince(clientUserId, prefix,
                since == null ? Instant.EPOCH : since);
            Map<String, InboxItem> byId = new HashMap<>();
            items.findAllById(jobs.stream().map(j -> j.sourceRef().substring(prefix.length())).distinct().toList())
                .forEach(i -> byId.put(i.getId(), i));
            List<PublishedPost> out = new ArrayList<>();
            for (PublishingService.JobView j : jobs) {
                InboxItem item = byId.get(j.sourceRef().substring(prefix.length()));
                if (item == null || !clientUserId.equals(item.getClientUserId()) || !agentId.equals(item.getAgentId())) continue;
                PostMetrics m = j.metrics();
                out.add(new PublishedPost(item.getId(), item.getTitle(), payload(item), j.network(), j.accountName(),
                    j.finishedAt(), m == null ? null : new Metrics(m.impressions(), m.reach(), m.reactions(), m.comments(),
                        m.shares(), m.saves(), m.clicks(), j.metricsAt())));
            }
            return out;
        }

        private Map<String, Object> payload(InboxItem item) {
            if (item.getPayloadJson() == null) return Map.of();
            try {
                return objectMapper.readValue(item.getPayloadJson(), new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                return Map.of();
            }
        }
    }

    /** The variant's text with its hashtags appended, as the client approved it. */
    static String postText(Map<String, Object> v) {
        StringBuilder text = new StringBuilder(String.valueOf(v.getOrDefault("text", "")).strip());
        if (v.get("hashtags") instanceof List<?> tags && !tags.isEmpty()) {
            List<String> marked = tags.stream().map(String::valueOf).map(String::strip).filter(s -> !s.isEmpty())
                .map(s -> s.startsWith("#") ? s : "#" + s).toList();
            if (!marked.isEmpty()) text.append("\n\n").append(String.join(" ", marked));
        }
        return text.toString();
    }

    static Instant parseAt(String at) {
        if (at == null || at.isBlank()) return null;
        try {
            return Instant.parse(at.trim());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The publishing time is not a valid date and time.");
        }
    }

    @RestController
    @RequestMapping("/api/client/standing/inbox/{itemId}/publish")
    @ConditionalOnClass(name = PUBLISHING)
    @RequiredArgsConstructor
    @Tag(name = "Standing agents (client)")
    public static class ClientRest {

        private final Service service;
        private final ClientAuthHelper clientAuth;

        @GetMapping
        @Operation(summary = "Where an approved post can go, and what was already scheduled or published")
        public PublishOptions options(@PathVariable String itemId, HttpServletRequest req) {
            return service.options(clientId(req), itemId);
        }

        @PostMapping
        @Operation(summary = "Publish an approved post now, or schedule it, to chosen accounts")
        public List<PublishingService.JobView> publish(@PathVariable String itemId, @RequestBody PublishRequest body,
                                                       HttpServletRequest req) {
            return service.publish(clientId(req), itemId, body);
        }

        @DeleteMapping("/{jobId}")
        @Operation(summary = "Cancel a scheduled post that has not gone out yet")
        public PublishingService.JobView cancel(@PathVariable String itemId, @PathVariable String jobId, HttpServletRequest req) {
            return service.cancel(clientId(req), itemId, jobId);
        }

        private String clientId(HttpServletRequest req) {
            return clientAuth.resolve(req).map(ClientUser::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in."));
        }

        @ExceptionHandler(ResponseStatusException.class)
        public ResponseEntity<Map<String, String>> refused(ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("message", e.getReason() == null ? "The request was refused." : e.getReason()));
        }
    }

    @RestController
    @RequestMapping("/api/connect/standing/inbox/{itemId}/publish")
    @ConditionalOnClass(name = {PUBLISHING, CONNECTORS})
    @RequiredArgsConstructor
    @Tag(name = "Standing agents connector")
    public static class ConnectorRest {

        private final Service service;

        @GetMapping
        public PublishOptions options(@PathVariable String itemId, HttpServletRequest req) {
            return service.options(ConnectorPrincipal.from(req).clientUserId(), itemId);
        }

        @PostMapping
        public List<PublishingService.JobView> publish(@PathVariable String itemId, @RequestBody PublishRequest body,
                                                       HttpServletRequest req) {
            return service.publish(ConnectorPrincipal.from(req).clientUserId(), itemId, body);
        }

        @DeleteMapping("/{jobId}")
        public PublishingService.JobView cancel(@PathVariable String itemId, @PathVariable String jobId, HttpServletRequest req) {
            return service.cancel(ConnectorPrincipal.from(req).clientUserId(), itemId, jobId);
        }
    }

    @Component
    @ConditionalOnClass(name = {PUBLISHING, CONNECTORS})
    @RequiredArgsConstructor
    public static class Tools implements ClientToolContributor {

        private final Service service;

        @Override
        public List<McpTool> tools(String client) {
            return List.of(
                new McpTool("standing_publish_options",
                    "For an inbox post: each channel version, the connected accounts it can go to, and what was already scheduled or published.",
                    schema(Map.of("itemId", string("The inbox item's id")), "itemId"),
                    args -> service.options(client, text(args, "itemId"))),
                new McpTool("standing_publish_item",
                    "Publish an approved inbox post, or schedule it, to one connected account. Call once per account.",
                    schema(Map.of(
                        "itemId", string("The inbox item's id"),
                        "variant", integer("Index of the channel version, from standing_publish_options"),
                        "accountId", string("A connected account id, from standing_publish_options"),
                        "at", string("Optional ISO-8601 time, e.g. 2026-11-02T10:00:00Z; omitted means now")),
                        "itemId", "variant", "accountId"),
                    args -> service.publish(client, text(args, "itemId"), new PublishRequest(
                        List.of(new Target(args.path("variant").asInt(-1), text(args, "accountId"))),
                        optionalText(args, "at")))));
        }
    }
}
