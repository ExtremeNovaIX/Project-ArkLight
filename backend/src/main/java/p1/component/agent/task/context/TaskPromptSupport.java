package p1.component.agent.task.context;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

public final class TaskPromptSupport {

    private TaskPromptSupport() {
    }

    public static String renderTaskGoal(String userQuestion, String agentRequest) {
        return """
                <task_goal>
                <user_question>
                %s
                </user_question>
                <rp_agent_request>
                %s
                </rp_agent_request>
                </task_goal>
                """.formatted(userQuestion, agentRequest);
    }

    public static String renderCurrentInstructionSection(String agentRequest, String currentInstruction) {
        String normalizedInstruction = trimToEmpty(currentInstruction);
        if (!StringUtils.hasText(normalizedInstruction) || normalizedInstruction.equals(trimToEmpty(agentRequest))) {
            return "";
        }
        return """
                <current_instruction>
                %s
                </current_instruction>
                """.formatted(normalizedInstruction);
    }

    public static String formatList(List<String> values) {
        List<String> safeValues = values == null ? List.of() : values;
        if (safeValues.isEmpty()) {
            return "[]";
        }

        List<String> normalized = new ArrayList<>();
        for (String value : safeValues) {
            String trimmed = trimToEmpty(value);
            if (StringUtils.hasText(trimmed)) {
                normalized.add(trimmed);
            }
        }
        return normalized.toString();
    }

    public static String trimToEmpty(String text) {
        return text == null ? "" : text.trim();
    }
}
