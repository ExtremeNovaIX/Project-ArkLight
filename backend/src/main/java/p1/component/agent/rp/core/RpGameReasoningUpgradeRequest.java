package p1.component.agent.rp.core;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record RpGameReasoningUpgradeRequest(RpGameReasoningEffort effort, String reason) {
    private static final Pattern REQUEST_PATTERN = Pattern.compile(
            "<reasoning_effort_request\\s+level=\\\"([^\\\"]+)\\\"\\s*>(.*?)</reasoning_effort_request>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public static Optional<RpGameReasoningUpgradeRequest> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = REQUEST_PATTERN.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return RpGameReasoningEffort.parse(matcher.group(1))
                .filter(effort -> effort != RpGameReasoningEffort.LOW)
                .map(effort -> new RpGameReasoningUpgradeRequest(effort, compactReason(matcher.group(2))));
    }

    private static String compactReason(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160);
    }
}