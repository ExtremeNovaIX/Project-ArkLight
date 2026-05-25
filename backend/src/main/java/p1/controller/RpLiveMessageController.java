package p1.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import p1.component.agent.rp.proactive.RpLiveMessageHub;

/**
 * RP 主动消息实时订阅接口。
 */
@RestController
@CrossOrigin
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class RpLiveMessageController {

    private final RpLiveMessageHub liveMessageHub;

    /**
     * 打开一个按 RP 会话分流的 SSE 订阅。
     *
     * @param sessionId     RP 会话 id
     * @param characterName 当前角色名
     * @return SSE emitter
     */
    @GetMapping("/live")
    public SseEmitter live(@RequestParam(required = false) String sessionId,
                           @RequestParam(required = false) String characterName,
                           @RequestParam(required = false) Boolean shortMode) {
        return liveMessageHub.subscribe(sessionId, characterName, shortMode);
    }
}
