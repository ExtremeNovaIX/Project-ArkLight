package p1.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import p1.component.agent.tts.TtsAudioHub;

/**
 * TTS 音频实时订阅接口。
 */
@RestController
@CrossOrigin
@RequestMapping("/api/tts")
@RequiredArgsConstructor
public class TtsAudioController {

    private final TtsAudioHub audioHub;

    /**
     * 打开一个按 RP 会话分流的 TTS 音频 SSE 订阅。
     *
     * @param sessionId RP 会话 id
     * @return SSE emitter
     */
    @GetMapping("/live")
    public SseEmitter live(@RequestParam(required = false) String sessionId) {
        return audioHub.subscribe(sessionId);
    }
}
