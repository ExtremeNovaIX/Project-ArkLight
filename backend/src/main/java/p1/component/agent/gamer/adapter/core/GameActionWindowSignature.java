package p1.component.agent.gamer.adapter.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Collections;
import java.util.StringJoiner;

/**
 * 表示一批操作依赖的本地行动窗口。
 * <p>
 * 行动窗口只描述“当前是否仍是同一类可执行局面”，不承载完整游戏状态。
 * 具体游戏适配器应只放入会影响本地操作合法性的稳定字段，避免队友行动、
 * 动画日志或血量变化误触发旧状态中断。
 */
public record GameActionWindowSignature(
        boolean checked,
        String category,
        Map<String, String> fields
) {

    public static GameActionWindowSignature unchecked() {
        return new GameActionWindowSignature(false, "unchecked", Map.of());
    }

    public static GameActionWindowSignature of(String category, Map<String, ?> fields) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (fields != null) {
            fields.forEach((key, value) -> {
                if (key != null && value != null) {
                    normalized.put(key, String.valueOf(value));
                }
            });
        }
        return new GameActionWindowSignature(true, blankTo(category, "unknown"), normalized);
    }

    public GameActionWindowSignature {
        category = blankTo(category, checked ? "unknown" : "unchecked");
        fields = fields == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    public boolean sameWindow(GameActionWindowSignature other) {
        if (!checked || other == null || !other.checked()) {
            return true;
        }
        return Objects.equals(category, other.category())
                && Objects.equals(fields, other.fields());
    }

    public String compact() {
        if (!checked) {
            return "unchecked";
        }
        StringJoiner joiner = new StringJoiner("|");
        joiner.add(category);
        fields.forEach((key, value) -> joiner.add(key + "=" + value));
        return joiner.toString();
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
