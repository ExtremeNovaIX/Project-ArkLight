package p1.component.agent.tts;

import java.util.function.Consumer;

/**
 * TTS provider 抽象。
 * <p>
 * provider 只负责把文本变成音频，不关心 RP、前端或 SSE。云厂商、本地命令行、
 * 本地 WebSocket 服务都应该实现这个接口。
 */
public interface TtsProvider {

    /**
     * provider 配置名称。
     *
     * @return 例如 gpt-sovits-http 或 voxcpm2-http
     */
    String providerName();

    /**
     * 当前 provider 是否具备合成条件。
     *
     * @return true 表示可以合成
     */
    boolean isAvailable();

    /**
     * 合成一段文本，并把音频块交给上层。
     *
     * @param request 合成请求
     * @param audioConsumer 音频块消费者
     * @throws Exception 合成失败
     */
    void synthesize(TtsSynthesisRequest request, Consumer<TtsAudioFrame> audioConsumer) throws Exception;
}
