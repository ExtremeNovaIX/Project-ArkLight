package p1.component.agent.tts;

import lombok.CustomLog;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static p1.utils.SessionUtil.normalizeSessionId;

/**
 * TTS 音频 SSE 分发中心。
 * <p>
 * 前端按 RP 会话订阅 {@code /api/tts/live} 后，后端会把 RP 发言期间生成的音频块推送到对应会话。
 * 该组件只处理音频事件，不参与文本显示和 RP 主动发言调度。
 */
@Component
@CustomLog
public class TtsAudioHub {

    private static final long EMITTER_TIMEOUT_MS = 0L;

    private final ConcurrentMap<String, List<SseEmitter>> emittersBySession = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> playbackSequenceBySession = new ConcurrentHashMap<>();

    /**
     * 创建一个 TTS 音频订阅。
     *
     * @param sessionId RP 会话 id
     * @return SSE emitter
     */
    public SseEmitter subscribe(String sessionId) {
        String normalizedSessionId = normalizeSessionId(sessionId);
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emittersBySession.computeIfAbsent(normalizedSessionId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);

        Runnable cleanup = () -> remove(normalizedSessionId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());

        try {
            emitter.send(SseEmitter.event()
                    .name("ready")
                    .data("tts-live-ready"));
        } catch (IOException e) {
            remove(normalizedSessionId, emitter);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /**
     * 判断指定会话是否存在音频订阅者。
     *
     * @param sessionId RP 会话 id
     * @return true 表示至少有一个前端正在监听音频
     */
    public boolean hasSubscribers(String sessionId) {
        List<SseEmitter> emitters = emittersBySession.get(normalizeSessionId(sessionId));
        return emitters != null && !emitters.isEmpty();
    }

    /**
     * 发布一段音频。
     *
     * @param sessionId RP 会话 id
     * @param source 发言来源
     * @param sequence 音频序号
     * @param text 本段音频对应文本
     * @param frame 音频帧
     */
    public synchronized void publishAudio(String sessionId, String source, long sequence, String text, TtsAudioFrame frame) {
        String normalizedSessionId = normalizeSessionId(sessionId);
        if (frame == null || frame.audioBytes() == null || frame.audioBytes().length == 0) {
            return;
        }
        publish(new TtsAudioMessage(
                normalizedSessionId,
                safeSource(source),
                sequence,
                nextPlaybackSequence(normalizedSessionId),
                safeMediaType(frame.mediaType()),
                frame.sampleRate(),
                Base64.getEncoder().encodeToString(frame.audioBytes()),
                text == null ? "" : text,
                false,
                Instant.now()));
    }

    /**
     * 发布一次发言结束标记。
     *
     * @param sessionId RP 会话 id
     * @param source 发言来源
     * @param sequence 最后序号
     */
    public synchronized void publishFinal(String sessionId, String source, long sequence) {
        String normalizedSessionId = normalizeSessionId(sessionId);
        publish(new TtsAudioMessage(
                normalizedSessionId,
                safeSource(source),
                sequence,
                nextPlaybackSequence(normalizedSessionId),
                "application/octet-stream",
                0,
                "",
                "",
                true,
                Instant.now()));
    }

    /**
     * 向指定会话广播 TTS 消息。
     *
     * @param message TTS 消息
     */
    private void publish(TtsAudioMessage message) {
        List<SseEmitter> emitters = emittersBySession.getOrDefault(message.sessionId(), List.of());
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("tts-audio")
                        .data(message));
            } catch (IOException e) {
                log.debug("[TTS] SSE 投递失败，移除连接: session={}, reason={}",
                        message.sessionId(), e.getMessage());
                remove(message.sessionId(), emitter);
                emitter.completeWithError(e);
            }
        }
    }

    /**
     * 移除失效订阅。
     *
     * @param sessionId RP 会话 id
     * @param emitter SSE emitter
     */
    private void remove(String sessionId, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersBySession.get(sessionId);
        if (emitters == null || !emitters.remove(emitter)) {
            return;
        }
        if (emitters.isEmpty()) {
            emittersBySession.remove(sessionId, emitters);
            playbackSequenceBySession.remove(sessionId);
        }
    }

    private long nextPlaybackSequence(String sessionId) {
        return playbackSequenceBySession
                .computeIfAbsent(sessionId, ignored -> new AtomicLong())
                .incrementAndGet();
    }

    private String safeSource(String source) {
        return source == null || source.isBlank() ? "unknown" : source.trim();
    }

    private String safeMediaType(String mediaType) {
        return mediaType == null || mediaType.isBlank() ? "audio/wav" : mediaType.trim();
    }
}
