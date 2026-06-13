package p1.component.agent.tts;

import java.time.Instant;

/**
 * 推送给前端播放的一段 TTS 音频。
 *
 * @param sessionId RP 会话 id
 * @param source 发言来源
 * @param sequence 当前发言内的音频序号
 * @param playbackSequence 当前 RP 会话内的播放序号
 * @param mediaType 音频 MIME 类型
 * @param sampleRate 采样率；自描述格式可为 0
 * @param audioBase64 base64 编码后的音频字节
 * @param text 本段音频对应的文本
 * @param finalChunk 是否为本次发言结束标记
 * @param createdAt 创建时间
 */
public record TtsAudioMessage(
        String sessionId,
        String source,
        long sequence,
        long playbackSequence,
        String mediaType,
        int sampleRate,
        String audioBase64,
        String text,
        boolean finalChunk,
        Instant createdAt
) {
}
