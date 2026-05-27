package p1.component.agent.tts;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * TTS 文本规范化。
 * <p>
 * RP 可见文本里常带有情绪标签，例如 {@code [开心]}。这些标签适合前端表情切换，
 * 不适合直接朗读，因此进入 TTS 前统一剥离。
 */
@Component
public class TtsTextNormalizer {

    private static final Pattern EMOTION_TAG = Pattern.compile("\\[[^\\]\\r\\n]{1,24}]|【[^】\\r\\n]{1,24}】");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]{1,80})]\\([^)]{1,200}\\)");
    private static final Pattern EXTRA_SPACES = Pattern.compile("[ \\t\\x0B\\f\\r]+");

    /**
     * 清理模型输出片段。
     *
     * @param text 原始片段
     * @return 适合朗读的片段
     */
    public String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = MARKDOWN_LINK.matcher(text).replaceAll("$1");
        normalized = EMOTION_TAG.matcher(normalized).replaceAll("");
        normalized = normalized
                .replace("*", "")
                .replace("`", "")
                .replace("#", "");
        normalized = EXTRA_SPACES.matcher(normalized).replaceAll(" ");
        return normalized.trim();
    }
}
