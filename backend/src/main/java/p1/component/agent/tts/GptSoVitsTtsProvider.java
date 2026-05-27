package p1.component.agent.tts;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * GPT-SoVITS HTTP TTS provider。
 * <p>
 * 本实现只调用外部 GPT-SoVITS `api_v2.py` 暴露的 `/tts` 接口，不内置模型、不下载权重。
 * Java 侧按 RP 流式文本切成短句后逐段调用，以降低用户听到首段语音的等待时间。
 */
@Component
@RequiredArgsConstructor
public class GptSoVitsTtsProvider implements TtsProvider {

    public static final String PROVIDER_NAME = "gpt-sovits-http";
    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String ACCEPT_HEADER = "Accept";

    private final TtsConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /**
     * 返回 GPT-SoVITS provider 名称。
     *
     * @return provider 名称
     */
    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    /**
     * 判断 GPT-SoVITS provider 是否具备调用条件。
     *
     * @return true 表示已启用 GPT-SoVITS，且 base-url、参考音频和语言参数齐全
     */
    @Override
    public boolean isAvailable() {
        TtsConfig.GptSoVitsConfig gpt = config.getGptSoVits();
        return config.enabled()
                && hasText(gpt.getBaseUrl())
                && hasText(gpt.getRefAudioPath())
                && hasText(gpt.getTextLang())
                && hasText(gpt.getPromptLang());
    }

    /**
     * 调用 GPT-SoVITS `/tts` 接口合成一段短句。
     *
     * @param request       合成请求
     * @param audioConsumer 音频块消费者
     * @throws Exception GPT-SoVITS 调用失败或返回空音频
     */
    @Override
    public void synthesize(TtsSynthesisRequest request, Consumer<TtsAudioFrame> audioConsumer) throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException("GPT-SoVITS TTS provider 未启用或缺少必要配置");
        }

        TtsConfig.GptSoVitsConfig gpt = config.getGptSoVits();
        String mediaType = mediaTypeForFormat(gpt.getMediaType());
        HttpRequest httpRequest = HttpRequest.newBuilder(resolveTtsUri(gpt))
                .timeout(config.synthesisTimeout())
                .header(CONTENT_TYPE_HEADER, "application/json")
                .header(ACCEPT_HEADER, mediaType)
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(request, gpt), StandardCharsets.UTF_8))
                .build();

        HttpResponse<byte[]> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("GPT-SoVITS TTS 调用失败: status=" + response.statusCode()
                    + ", body=" + safeBody(response.body()));
        }
        byte[] audioBytes = response.body();
        if (audioBytes == null || audioBytes.length == 0) {
            throw new IllegalStateException("GPT-SoVITS TTS 返回空音频");
        }

        String responseMediaType = response.headers()
                .firstValue(CONTENT_TYPE_HEADER)
                .map(GptSoVitsTtsProvider::stripContentTypeParameters)
                .filter(GptSoVitsTtsProvider::hasText)
                .orElse(mediaType);
        audioConsumer.accept(new TtsAudioFrame(responseMediaType, 0, audioBytes));
    }

    /**
     * 构造 GPT-SoVITS `/tts` 请求体。
     *
     * @param request 合成请求
     * @param gpt     GPT-SoVITS 配置
     * @return JSON 字符串
     * @throws IOException JSON 序列化失败
     */
    private String buildRequestBody(TtsSynthesisRequest request, TtsConfig.GptSoVitsConfig gpt) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", request.text());
        payload.put("text_lang", gpt.getTextLang());
        payload.put("ref_audio_path", gpt.getRefAudioPath());
        putIfNotEmpty(payload, "aux_ref_audio_paths", gpt.getAuxRefAudioPaths());
        putIfHasText(payload, "prompt_text", gpt.getPromptText());
        payload.put("prompt_lang", gpt.getPromptLang());
        payload.put("top_k", gpt.getTopK());
        payload.put("top_p", gpt.getTopP());
        payload.put("temperature", gpt.getTemperature());
        payload.put("text_split_method", gpt.getTextSplitMethod());
        payload.put("batch_size", gpt.getBatchSize());
        payload.put("batch_threshold", gpt.getBatchThreshold());
        payload.put("split_bucket", gpt.isSplitBucket());
        payload.put("speed_factor", gpt.getSpeedFactor());
        payload.put("fragment_interval", gpt.getFragmentInterval());
        payload.put("seed", gpt.getSeed());
        payload.put("media_type", normalizeFormat(gpt.getMediaType()));
        payload.put("streaming_mode", gpt.getStreamingMode());
        payload.put("parallel_infer", gpt.isParallelInfer());
        payload.put("repetition_penalty", gpt.getRepetitionPenalty());
        payload.put("sample_steps", gpt.getSampleSteps());
        payload.put("super_sampling", gpt.isSuperSampling());
        payload.put("overlap_length", gpt.getOverlapLength());
        payload.put("min_chunk_length", gpt.getMinChunkLength());
        return objectMapper.writeValueAsString(payload);
    }

    /**
     * 解析 `/tts` endpoint。
     *
     * @param gpt GPT-SoVITS 配置
     * @return `/tts` URI
     */
    private URI resolveTtsUri(TtsConfig.GptSoVitsConfig gpt) {
        return URI.create(gpt.getBaseUrl().trim().replaceAll("/+$", "") + "/tts");
    }

    private static void putIfHasText(Map<String, Object> payload, String key, String value) {
        if (hasText(value)) {
            payload.put(key, value.trim());
        }
    }

    private static void putIfNotEmpty(Map<String, Object> payload, String key, List<String> value) {
        if (value != null && !value.isEmpty()) {
            payload.put(key, value);
        }
    }

    private static String mediaTypeForFormat(String format) {
        return switch (normalizeFormat(format)) {
            case "aac" -> "audio/aac";
            case "ogg" -> "audio/ogg";
            case "raw" -> "audio/L16";
            default -> "audio/wav";
        };
    }

    private static String normalizeFormat(String format) {
        if (!hasText(format)) {
            return "wav";
        }
        String normalized = format.trim().toLowerCase();
        return switch (normalized) {
            case "wav", "raw", "ogg", "aac" -> normalized;
            default -> "wav";
        };
    }

    private static String stripContentTypeParameters(String contentType) {
        int delimiter = contentType.indexOf(';');
        return delimiter >= 0 ? contentType.substring(0, delimiter).trim() : contentType.trim();
    }

    private static String safeBody(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        String text = new String(body, StandardCharsets.UTF_8);
        return text.length() > 500 ? text.substring(0, 500) + "..." : text;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
