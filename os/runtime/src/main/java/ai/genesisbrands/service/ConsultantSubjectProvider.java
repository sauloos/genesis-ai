package ai.genesisbrands.service;

import java.util.List;

/**
 * Resolves the {@link ConsultantSubject} a conversation is scoped to, and manages the
 * set of subjects a tenant exposes. The platform's {@link ConsultantService} owns the
 * mechanics (retrieval, history, streaming); the tenant owns what a subject is and how
 * to look one up — mirrors {@link TenantConsultantConfig}, which owns the persona rather
 * than the mechanics.
 * <p>
 * No default implementation is registered — a tenant that supplies one gets a working
 * {@code ConsultantService} bean and a working {@code /api/consultant/**} REST surface
 * (see {@code ConsultantServiceConfiguration} and {@code ConsultantController}); a bare
 * platform deployment with no subject concept simply has no consultant capability.
 */
public interface ConsultantSubjectProvider {

    ConsultantSubject find(String subjectId);

    List<ConsultantSubjectSummary> list();

    ConsultantSubjectSummary create(String name, String industry, String audience, String brief);

    void delete(String subjectId);
}
