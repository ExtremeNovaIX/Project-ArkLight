package p1.component.agent.rp.proactive;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static p1.utils.ReplyUtil.segment;
import static p1.utils.SessionUtil.normalizeSessionId;

/**
 * RP 主动消息的 SSE 分发中心。
 * <p>
 * 后端主动生成的 RP 文本会在这里按 session 投递给已订阅前端；
 * 每个前端连接独立清理，单个连接断开不会影响同会话的其他窗口。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RpLiveMessageHub {

    private static final long EMITTER_TIMEOUT_MS = 0L;

    private final RpProactiveSessionRegistry sessionRegistry;
    private final ConcurrentMap<String, List<SseEmitter>> emittersBySession = new ConcurrentHashMap<>();

    /**
     * 创建一个 RP 实时消息订阅。
     *
     * @param sessionId     RP 会话 id
     * @param characterName 当前角色名
     * @param shortMode    前端订阅时的短句模式初值；后续聊天请求仍可覆盖
     * @return 可由 Spring MVC 保持的 SSE emitter
     */
    public SseEmitter subscribe(String sessionId, String characterName, Boolean shortMode) {
        String normalizedSessionId = normalizeSessionId(sessionId);
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emittersBySession.computeIfAbsent(normalizedSessionId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        sessionRegistry.openSubscription(normalizedSessionId, characterName, shortMode);

        Runnable cleanup = () -> remove(normalizedSessionId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());

        try {
            // 首帧让 EventSource 尽快进入打开状态，也便于定位前端是否真正连上。
            emitter.send(SseEmitter.event()
                    .name("ready")
                    .data("rp-live-ready"));
        } catch (IOException e) {
            remove(normalizedSessionId, emitter);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /**
     * 向指定 RP 会话广播主动消息。
     *
     * @param sessionId RP 会话 id
     * @param source    消息来源
     * @param content   RP 最终文本
     * @param shortMode true 表示按短句模式投递分段
     */
    public void publish(String sessionId, String source, String content, boolean shortMode) {
        String normalizedSessionId = normalizeSessionId(sessionId);
        if (content == null || content.isBlank()) {
            return;
        }

        RpLiveMessage message = new RpLiveMessage(
                normalizedSessionId,
                source,
                content.trim(),
                segment(content, shortMode),
                Instant.now());
        List<SseEmitter> emitters = emittersBySession.getOrDefault(normalizedSessionId, List.of());
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("rp-message")
                        .data(message));
            } catch (IOException e) {
                log.debug("[RP主动发言] SSE 投递失败，移除连接: session={}, reason={}",
                        normalizedSessionId, e.getMessage());
                remove(normalizedSessionId, emitter);
                emitter.completeWithError(e);
            }
        }
    }

    /**
     * 移除一个已失效订阅。
     *
     * @param sessionId RP 会话 id
     * @param emitter   失效 emitter
     */
    private void remove(String sessionId, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersBySession.get(sessionId);
        if (emitters == null || !emitters.remove(emitter)) {
            return;
        }
        if (emitters.isEmpty()) {
            emittersBySession.remove(sessionId, emitters);
        }
        sessionRegistry.closeSubscription(sessionId);
    }
}
