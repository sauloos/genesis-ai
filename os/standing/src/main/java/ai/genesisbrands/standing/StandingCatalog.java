package ai.genesisbrands.standing;

import ai.genesisbrands.platform.AgentCustomOption;
import ai.genesisbrands.platform.CoreAgent;
import ai.genesisbrands.platform.standing.Cadence;
import ai.genesisbrands.platform.standing.StandingAgent;
import ai.genesisbrands.service.AgentCatalogService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Joins the adopted {@link StandingAgent} beans with their admin {@link StandingAgentOffer}.
 * An agent is offered to clients only while its offer is enabled AND it is available for live
 * view: a standing run is a customer-facing execution path, so the live-view flag stays the
 * outer security boundary, exactly as for the other customer surfaces.
 */
@Service
public class StandingCatalog {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    private final List<StandingAgent> agents;
    private final List<CoreAgent> coreAgents;
    private final StandingOfferRepository offers;
    private final AgentCatalogService agentCatalog;
    private final StandingEntitlements entitlements;
    private final ObjectMapper objectMapper;

    public StandingCatalog(List<StandingAgent> agents, List<CoreAgent> coreAgents, StandingOfferRepository offers,
                           AgentCatalogService agentCatalog, StandingEntitlements entitlements, ObjectMapper objectMapper) {
        this.agents = agents;
        this.coreAgents = coreAgents;
        this.offers = offers;
        this.agentCatalog = agentCatalog;
        this.entitlements = entitlements;
        this.objectMapper = objectMapper;
    }

    public List<StandingAgent> all() {
        return agents;
    }

    public Optional<StandingAgent> find(String agentId) {
        return agents.stream().filter(a -> a.agentId().equals(agentId)).findFirst();
    }

    public StandingAgent require(String agentId) {
        return find(agentId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No standing agent: " + agentId));
    }

    public Optional<CoreAgent> describe(String agentId) {
        return coreAgents.stream().filter(a -> a.agentId().equals(agentId)).findFirst();
    }

    public String displayName(String agentId) {
        return describe(agentId).map(CoreAgent::displayName).orElse(agentId);
    }

    /** The offer row, or a disabled default when the admin has never configured one. */
    public StandingAgentOffer offer(String agentId) {
        return offers.findById(agentId).orElseGet(() -> {
            StandingAgentOffer fresh = new StandingAgentOffer();
            fresh.setAgentId(agentId);
            return fresh;
        });
    }

    public boolean isOffered(String agentId) {
        return find(agentId).isPresent() && offer(agentId).isEnabled() && agentCatalog.isAvailableForLiveView(agentId);
    }

    /** Offered, and this client has bought what the offer requires. */
    public boolean isOfferedTo(String agentId, String clientUserId) {
        return isOffered(agentId) && entitlements.entitled(clientUserId, offer(agentId));
    }

    /** 404 for an agent the client may not use, indistinguishable from one that does not exist. */
    public StandingAgent requireOfferedTo(String agentId, String clientUserId) {
        if (!isOfferedTo(agentId, clientUserId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not available: " + agentId);
        return require(agentId);
    }

    /** The agent's cadences, narrowed by the admin's choice when there is one. */
    public Set<Cadence> cadences(StandingAgent agent, StandingAgentOffer offer) {
        Set<Cadence> own = EnumSet.copyOf(agent.allowedCadences());
        Set<Cadence> admin = parseCadences(offer.getAllowedCadences());
        if (!admin.isEmpty()) own.retainAll(admin);
        return own;
    }

    public Cadence defaultCadence(StandingAgent agent, StandingAgentOffer offer) {
        Set<Cadence> allowed = cadences(agent, offer);
        if (allowed.contains(agent.defaultCadence()) || allowed.isEmpty()) return agent.defaultCadence();
        return allowed.iterator().next();
    }

    public Set<String> locked(StandingAgentOffer offer) {
        return splitCsv(offer.getLockedOptions());
    }

    public Map<String, String> adminDefaults(StandingAgentOffer offer) {
        return readMap(offer.getDefaultOptionsJson());
    }

    /** The options the agent's own declaration, the admin defaults and the client's values add up to. */
    public Map<String, String> effectiveOptions(StandingAgent agent, StandingAgentOffer offer, Map<String, String> clientValues) {
        return OptionValues.resolve(agent.subscriptionOptions(), adminDefaults(offer), locked(offer), clientValues);
    }

    /** The declared options with the admin's defaults in place of the agent's own. */
    public List<AgentCustomOption> optionsWithAdminDefaults(StandingAgent agent, StandingAgentOffer offer) {
        Map<String, String> defaults = adminDefaults(offer);
        return agent.subscriptionOptions().stream()
            .map(o -> defaults.containsKey(o.key())
                ? new AgentCustomOption(o.key(), o.label(), o.type(), o.allowedValues(), defaults.get(o.key()), o.helpText())
                : o)
            .toList();
    }

    public Map<String, String> readMap(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, STRING_MAP);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    public String writeMap(Map<String, String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? Map.of() : values);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Set<Cadence> parseCadences(String csv) {
        Set<Cadence> out = EnumSet.noneOf(Cadence.class);
        for (String name : splitCsv(csv)) {
            try {
                out.add(Cadence.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                // A cadence removed from the enum since the admin saved it.
            }
        }
        return out;
    }

    static Set<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
            .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
