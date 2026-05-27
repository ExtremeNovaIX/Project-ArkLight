package p1.component.agent.tts;

/**
 * provider 返回的一段音频数据。
 *
 * @param mediaType 音频 MIME 类型，例如 audio/wav
 * @param sampleRate 采样率；WAV 等自描述格式可填 0
 * @param audioBytes 音频字节
 */
public record TtsAudioFrame(
        String mediaType,
        int sampleRate,
        byte[] audioBytes
) {
}
