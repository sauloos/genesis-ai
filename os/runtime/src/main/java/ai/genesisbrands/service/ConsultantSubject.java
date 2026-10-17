package ai.genesisbrands.service;

/**
 * The generic subject a consultant conversation is scoped to (a brand, or any
 * other tenant-defined concept). {@link ConsultantService} only ever sees this
 * shape — the tenant owns what a "subject" actually is via {@link ConsultantSubjectProvider}.
 */
public record ConsultantSubject(String id, String name, String industry, String audience, String brief) {
}
