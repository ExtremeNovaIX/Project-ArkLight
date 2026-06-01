package p1.utils;

import java.util.Arrays;
import java.util.List;

/**
 * RP 回复分段器。
 * <p>
 * 普通聊天 HTTP 返回和主动发言 SSE 投递都通过该组件执行短句模式切分，
 * 避免两条投递链路对同一段 RP 文本给出不同展示结果。
 */
public class ReplyUtil {

    private static final String SHORT_MODE_SPLIT_REGEX =
            "(?<=[。！？?!;；…])(?![。！？?!;；…])|(?<=\\.)(?![。！？?!;；…\\.0-9])|(?=\\[)";

    /**
     * 按展示模式切分一段 RP 回复。
     *
     * @param rawReply  模型原始回复
     * @param shortMode true 表示逐短句展示
     * @return 可直接投递给前端的文本段
     */
    public static List<String> segment(String rawReply, boolean shortMode) {
        String reply = rawReply == null ? "" : rawReply.trim();
        if (!shortMode) {
            return List.of(reply);
        }
        return Arrays.stream(reply.split(SHORT_MODE_SPLIT_REGEX))
                .filter(segment -> !segment.isBlank())
                .map(String::trim)
                .map(segment -> segment.replace("。", ""))
                .filter(segment -> !segment.isBlank())
                .toList();
    }
}
