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
import java.util.Map;
import java.util.function.Consumer;

/**
 * OpenBMB VoxCPM2 HTTP TTS provider。
 * <p>
 * 该 provider 只面向 OpenBMB/VoxCPM 仓库的 VoxCPM2，不兼容旧 VoxCPM、VoxCPM.cpp 或其他
 * OpenAI-compatible 变体。外部 sidecar 应基于 VoxCPM2 官方 Python API 长驻加载模型，
 * 并暴露 {@code POST /tts} 接口返回音频字节。
 */
@Component
@RequiredArgsConstructor
public class VoxCpm2TtsProvider implements TtsProvider {

    public static final String PROVIDER_NAME = "voxcpm2-http";
    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String ACCEPT_HEADER = "Accept";

    private final TtsConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /**
     * 返回 VoxCPM2 provider 名称。
     *
     * @return provider 名称
     */
    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    /**
     * 判断 VoxCPM2 provider 是否具备调用条件。
     *
     * @return true 表示已启用 TTS 且 VoxCPM2 base-url 存在
     */
    @Override
    public boolean isAvailable() {
        TtsConfig.VoxCpm2Config vox = config.getVoxCpm2();
        return config.enabled() && hasText(vox.getBaseUrl());
    }

    /**
     * 调用 VoxCPM2 sidecar 合成短句。
     *
     * @param request       合成请求
     * @param audioConsumer 音频消费者
     * @throws Exception VoxCPM2 调用失败或返回空音频
     */
    @Override
    public void synthesize(TtsSynthesisRequest request, Consumer<TtsAudioFrame> audioConsumer) throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException("VoxCPM2 TTS provider 未启用或缺少 base-url");
        }

        TtsConfig.VoxCpm2Config vox = config.getVoxCpm2();
        String mediaType = mediaTypeForFormat(vox.getMediaType());
        HttpRequest httpRequest = HttpRequest.newBuilder(resolveTtsUri(vox))
                .timeout(config.synthesisTimeout())
                .header(CONTENT_TYPE_HEADER, "application/json")
                .header(ACCEPT_HEADER, mediaType)
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(request, vox), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("VoxCPM2 TTS 调用失败: status=" + response.statusCode()
                    + ", body=" + safeBody(response.body()));
        }
        byte[] audioBytes = response.body();
        if (audioBytes == null || audioBytes.length == 0) {
            throw new IllegalStateException("VoxCPM2 TTS 返回空音频");
        }

        String responseMediaType = response.headers()
                .firstValue(CONTENT_TYPE_HEADER)
                .map(VoxCpm2TtsProvider::stripContentTypeParameters)
                .filter(VoxCpm2TtsProvider::hasText)
                .orElse(mediaType);
        audioConsumer.accept(new TtsAudioFrame(responseMediaType, 0, audioBytes));
    }

    /**
     * 构造 VoxCPM2 sidecar 请求体。
     *
     * @param request 合成请求
     * @param vox     VoxCPM2 配置
     * @return JSON 字符串
     * @throws IOException JSON 序列化失败
     */
    private String buildRequestBody(TtsSynthesisRequest request, TtsConfig.VoxCpm2Config vox) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", request.text());
        String controlInstruction = request.controlInstruction().isEmpty()
                ? vox.getControlInstruction()
                : request.controlInstruction();
        putIfHasText(payload, "control_instruction", controlInstruction);
        putIfHasText(payload, "reference_wav_path", vox.getReferenceWavPath());
        payload.put("cfg_value", vox.getCfgValue());
        payload.put("inference_timesteps", vox.getInferenceTimesteps());
        payload.put("normalize", vox.isNormalize());
        payload.put("denoise", vox.isDenoise());
        payload.put("media_type", normalizeFormat(vox.getMediaType()));
        if (vox.getExtraBody() != null && !vox.getExtraBody().isEmpty()) {
            payload.put("extra", vox.getExtraBody());
        }
        return objectMapper.writeValueAsString(payload);
    }

    /**
     * 解析 VoxCPM2 sidecar endpoint。
     *
     * @param vox VoxCPM2 配置
     * @return /tts URI
     */
    private URI resolveTtsUri(TtsConfig.VoxCpm2Config vox) {
        return URI.create(vox.getBaseUrl().trim().replaceAll("/+$", "") + "/tts");
    }

    private static void putIfHasText(Map<String, Object> payload, String key, String value) {
        if (hasText(value)) {
            payload.put(key, value.trim());
        }
    }

    private static String mediaTypeForFormat(String format) {
        return switch (normalizeFormat(format)) {
            case "mp3" -> "audio/mpeg";
            case "flac" -> "audio/flac";
            default -> "audio/wav";
        };
    }

    private static String normalizeFormat(String format) {
        if (!hasText(format)) {
            return "wav";
        }
        String normalized = format.trim().toLowerCase();
        return switch (normalized) {
            case "wav", "mp3", "flac" -> normalized;
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
