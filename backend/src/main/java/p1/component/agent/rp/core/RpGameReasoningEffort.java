package p1.component.agent.rp.core;

import java.util.Locale;
import java.util.Optional;

public enum RpGameReasoningEffort {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh");

    private final String apiValue;

    RpGameReasoningEffort(String apiValue) {
        this.apiValue = apiValue;
    }

    public String apiValue() {
        return apiValue;
    }

    public static Optional<RpGameReasoningEffort> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if ("med".equals(normalized)) {
            return Optional.of(MEDIUM);
        }
        if ("medium".equals(normalized)) {
            return Optional.of(MEDIUM);
        }
        if ("high".equals(normalized)) {
            return Optional.of(HIGH);
        }
        if ("xhigh".equals(normalized) || "x-high".equals(normalized) || "extra-high".equals(normalized)) {
            return Optional.of(XHIGH);
        }
        if ("low".equals(normalized)) {
            return Optional.of(LOW);
        }
        return Optional.empty();
    }
}