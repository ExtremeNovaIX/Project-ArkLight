package p1.component.agent.stt;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * ASR v2 sidecar 输出事件。
 * <p>
 * Java 侧只消费 ASR v2 字段；发往 Qt 的 final 布尔值由 stable 派生，用于 UI 展示。
 */
public record SttTranscriptEvent(
        String type,
        String segmentId,
        String source,
        String text,
        String speakerId,
        double speakerConfidence,
        String quality,
        boolean overlap,
        double noiseLevel,
        long startMs,
        long endMs,
        int revision,
        boolean stable,
        long latencyMs,
        String reason
) {

    public static SttTranscriptEvent partial(String text) {
        return new SttTranscriptEvent("partial", "", "", text, "UNKNOWN", 0.0d,
                "uncertain", false, 0.0d, -1L, -1L, 0, false, -1L, "test");
    }

    public static SttTranscriptEvent segment(String text) {
        return new SttTranscriptEvent("segment", "", "", text, "UNKNOWN", 0.0d,
                "clear", false, 0.0d, -1L, -1L, 0, true, -1L, "test");
    }

    public static SttTranscriptEvent fromJson(JsonNode node) {
        String type = node.path("type").asText("").trim();
        if (type.isEmpty()) {
            throw new IllegalArgumentException("ASR v2 event is missing type");
        }
        boolean stable = node.path("stable").asBoolean("segment".equals(type));
        return new SttTranscriptEvent(
                type,
                node.path("segmentId").asText(node.path("utteranceId").asText("")),
                node.path("source").asText(""),
                node.path("text").asText(""),
                node.path("speakerId").asText("UNKNOWN"),
                node.path("speakerConfidence").asDouble(node.path("confidence").asDouble(0.0d)),
                node.path("quality").asText("uncertain"),
                node.path("overlap").asBoolean(false),
                node.path("noiseLevel").asDouble(0.0d),
                node.path("startMs").asLong(-1L),
                node.path("endMs").asLong(-1L),
                node.path("revision").asInt(node.path("chunkIndex").asInt(0)),
                stable,
                node.path("latencyMs").asLong(-1L),
                node.path("reason").asText("")
        );
    }

    public boolean finalResult() {
        return stable && "segment".equals(type);
    }

    public boolean partialResult() {
        return "partial".equals(type);
    }

    public boolean diagnostic() {
        return "diagnostic".equals(type);
    }

    public boolean error() {
        return "error".equals(type);
    }

    public boolean routeableSegment() {
        return finalResult() && !text.isBlank();
    }

    public SttTranscriptEvent withText(String newText) {
        return new SttTranscriptEvent(type, segmentId, source, newText, speakerId, speakerConfidence,
                quality, overlap, noiseLevel, startMs, endMs, revision, stable, latencyMs, reason);
    }

    public SttTranscriptEvent withSource(String newSource) {
        return new SttTranscriptEvent(type, segmentId, newSource, text, speakerId, speakerConfidence,
                quality, overlap, noiseLevel, startMs, endMs, revision, stable, latencyMs, reason);
    }

    public SttTranscriptEvent asSegment() {
        return new SttTranscriptEvent("segment", segmentId, source, text, speakerId, speakerConfidence,
                quality, overlap, noiseLevel, startMs, endMs, revision, true, latencyMs, reason);
    }

    public SttTranscriptEvent asPartial() {
        return new SttTranscriptEvent("partial", segmentId, source, text, speakerId, speakerConfidence,
                quality, overlap, noiseLevel, startMs, endMs, revision, false, latencyMs, reason);
    }
}
