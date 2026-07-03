package p1.component.agent.rp.core;

public class RpGameReasoningUpgradeException extends RuntimeException {
    private final RpGameReasoningEffort effort;
    private final String reason;

    public RpGameReasoningUpgradeException(RpGameReasoningEffort effort, String reason) {
        super("RP requested reasoning effort upgrade to " + (effort == null ? "unknown" : effort.apiValue())
                + (reason == null || reason.isBlank() ? "" : ": " + reason.trim()));
        this.effort = effort == null ? RpGameReasoningEffort.MEDIUM : effort;
        this.reason = reason == null ? "" : reason.trim();
    }

    public RpGameReasoningEffort effort() {
        return effort;
    }

    public String reason() {
        return reason;
    }
}