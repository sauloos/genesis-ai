package ai.genesisbrands.platform.standing;

/** What can start a standing run. Only {@code SCHEDULE} is wired today. */
public enum TriggerKind {
    SCHEDULE,
    EVENT,
    UPLOAD
}
