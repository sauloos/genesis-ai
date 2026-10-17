package ai.genesisbrands.service;

import ai.genesisbrands.platform.AgentViewExtension;
import org.springframework.stereotype.Component;

/**
 * Genesis Brands' Consultant live view: wraps the core chat view with brand copy rather
 * than shipping a replacement at the core view's path.
 */
@Component
public class BrandConsultantLiveViewExtension implements AgentViewExtension {

    @Override public String agentId() { return "consultant"; }
    @Override public String view() { return "live"; }
    @Override public String module() { return "/agent-views/consultant/ext/brands-live.js"; }
}
