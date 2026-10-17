package ai.genesisbrands.service;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * Supplies the Spring AI tools a CUSTOMER-sourced Consultant chat may call on a subject's
 * behalf (e.g. regenerating a SLOT agent's output). Mirrors {@link ConsultantSubjectProvider}:
 * the platform owns the mechanics (deciding when tools get attached to the Prompt — see
 * {@code ConsultantService}), the tenant owns what those tools are and how they're built for a
 * given subject.
 * <p>
 * No default implementation is registered — a tenant that supplies one gets a working tool
 * roster attached to CUSTOMER-sourced chats whenever the admin has turned Consultant's
 * {@code toolsEnabled} flag on; a bare platform deployment, or one with no tool-bearing agents,
 * simply has no mutation capability from chat.
 */
public interface ConsultantToolProvider {

    List<ToolCallback> toolsFor(String subjectId);
}
