package p1.component.agent.rp.game.control;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * RP 流式控制块。
 * <p>
 * 该结构只表达 RP 的表达通道和操作通道；不承载 MCP 细节。
 */
public record RpControlBlock(
        Type type,
        VoiceKind voiceKind,
        String say,
        String doText,
        String check,
        String progress,
        String next,
        boolean commit
) {

    public enum Type {
        VOICE,
        ACT
    }

    public enum VoiceKind {
        CHAT,
        ASK
    }

    /**
     * 从模型流式 JSON 中解析控制块。非 voice/act JSON 会被忽略。
     *
     * @param json 候选 JSON
     * @return 有效控制块
     */
    public static Optional<RpControlBlock> from(JsonNode json) {
        if (json == null || !json.isObject()) {
            return Optional.empty();
        }
        String rawType = text(json, "type");
        if ("voice".equalsIgnoreCase(rawType)) {
            return Optional.of(new RpControlBlock(
                    Type.VOICE,
                    parseVoiceKind(text(json, "kind")),
                    text(json, "say"),
                    "",
                    "",
                    "",
                    "",
                    false));
        }
        if ("act".equalsIgnoreCase(rawType)) {
            return Optional.of(new RpControlBlock(
                    Type.ACT,
                    VoiceKind.CHAT,
                    "",
                    text(json, "do"),
                    firstNonBlank(text(json, "check"), text(json, "verify")),
                    text(json, "progress"),
                    text(json, "next"),
                    json.path("commit").asBoolean(false)));
        }
        return Optional.empty();
    }

    public boolean isVoice() {
        return type == Type.VOICE;
    }

    public boolean isAct() {
        return type == Type.ACT;
    }

    public boolean isAsk() {
        return isVoice() && voiceKind == VoiceKind.ASK;
    }

    public boolean isCommittedAction() {
        return isAct() && commit && hasActionText();
    }

    public boolean hasSpeech() {
        return isVoice() && say != null && !say.isBlank();
    }

    public boolean hasActionText() {
        return doText != null && !doText.isBlank();
    }

    private static VoiceKind parseVoiceKind(String rawKind) {
        return "ask".equalsIgnoreCase(rawKind) ? VoiceKind.ASK : VoiceKind.CHAT;
    }

    private static String text(JsonNode json, String field) {
        return json == null || field == null ? "" : json.path(field).asText("").trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
