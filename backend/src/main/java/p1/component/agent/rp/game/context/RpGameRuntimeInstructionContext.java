package p1.component.agent.rp.game.context;

import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * RP 游戏回合的临时运行指令。
 * <p>
 * 该指令只参与当前请求的动态上下文渲染，不进入长期记忆。
 */
@Service
public class RpGameRuntimeInstructionContext {

    private final ThreadLocal<String> instruction = new ThreadLocal<>();

    public <T> T withInstruction(String value, Supplier<T> action) {
        String previous = instruction.get();
        instruction.set(normalize(value));
        try {
            return action.get();
        } finally {
            if (previous == null) {
                instruction.remove();
            } else {
                instruction.set(previous);
            }
        }
    }

    public String render() {
        String current = instruction.get();
        if (current == null || current.isBlank()) {
            return "";
        }
        return """
                <game_loop_instruction>
                %s
                </game_loop_instruction>
                """.formatted(current).trim();
    }

    private String normalize(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized;
    }
}
