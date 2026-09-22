package ai.genesisbrands.service;

/**
 * Resolves the {@link ConsultantSubject} a conversation is scoped to. The platform's
 * {@link ConsultantService} owns the mechanics (retrieval, history, streaming); the
 * tenant owns what a subject is and how to look one up — mirrors {@link TenantConsultantConfig},
 * which owns the persona rather than the mechanics.
 * <p>
 * No default implementation is registered — a tenant that supplies one gets a working
 * {@code ConsultantService} bean (see {@code ConsultantServiceConfiguration}); a bare
 * platform deployment with no subject concept simply has no consultant capability.
 */
public interface ConsultantSubjectProvider {

    ConsultantSubject find(String subjectId);
}
