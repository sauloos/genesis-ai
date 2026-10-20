package ai.genesisbrands.platform.standing;

/**
 * What can start a standing run. An agent without {@code SCHEDULE} has no schedule at all: it
 * runs only when an event arrives. {@code UPLOAD} is reserved and not wired.
 */
public enum TriggerKind {
    SCHEDULE,
    EVENT,
    UPLOAD
}
