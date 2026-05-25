package p1.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import p1.component.agent.gamer.GamerAgentService;
import p1.component.agent.gamer.GamerRequestResolver;
import p1.component.agent.gamer.loop.ActiveGameSession;
import p1.component.agent.gamer.loop.GamerGameLoopService;

import java.util.Map;

/**
 * 通过 gamer agent 进行游戏操作的 REST 控制器。
 * <p>
 * 游戏指令：
 * POST /api/gamer/play   — 向 gamer agent 发送指令
 * <p>
 * 游戏循环控制：
 * POST /api/gamer/loop/start  — 启动自动游戏循环
 * POST /api/gamer/loop/stop   — 停止自动游戏循环
 * POST /api/gamer/loop/pause  — 暂停自动游戏循环
 * POST /api/gamer/loop/resume — 恢复自动游戏循环
 * GET  /api/gamer/loop/status — 查看循环状态
 */
@RestController
@CrossOrigin
@RequestMapping("/api/gamer")
@RequiredArgsConstructor
@Slf4j
public class GamerController {

    private final GamerAgentService gamerAgentService;
    private final GamerGameLoopService gameLoopService;
    private final GamerRequestResolver requestResolver;

    @PostMapping("/play")
    public ResponseEntity<?> play(@RequestBody GamerPlayRequest request) {
        if (request == null) return ResponseEntity.badRequest().body(Map.of("error", "请求体不能为空"));

        String gameName = requestResolver.resolveGameName(request.gameName);
        String sessionId = requestResolver.resolveSessionId(request.sessionId);
        String message = request.message;

        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message 不能为空"));
        }

        log.info("[游戏控制器] 收到游戏指令: game={}, session={}, message={}", gameName, sessionId, message);

        String response = gamerAgentService.play(gameName, sessionId, message);

        return ResponseEntity.ok(Map.of(
                "gameName", gameName,
                "sessionId", sessionId,
                "response", response
        ));
    }

    // ── 游戏循环控制 ──

    @PostMapping("/loop/start")
    public ResponseEntity<?> startLoop(@RequestBody(required = false) GamerLoopRequest request) {
        GameTarget target;
        try {
            target = resolveTarget(request);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        log.info("[游戏控制器] 启动游戏循环: game={}, session={}", target.gameName(), target.sessionId());
        ActiveGameSession session = gameLoopService.start(target.gameName(), target.sessionId(), target.rpSessionId());

        return ResponseEntity.ok(Map.of(
                "message", "游戏循环已启动",
                "session", sessionInfo(session)
        ));
    }

    @PostMapping("/loop/stop")
    public ResponseEntity<?> stopLoop(@RequestBody(required = false) GamerLoopRequest request) {
        GameTarget target;
        try {
            target = resolveTarget(request);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        log.info("[游戏控制器] 停止游戏循环: game={}, session={}", target.gameName(), target.sessionId());
        gameLoopService.stop(target.gameName(), target.sessionId());

        return ResponseEntity.ok(Map.of("message", "游戏循环已停止"));
    }

    @PostMapping("/loop/pause")
    public ResponseEntity<?> pauseLoop(@RequestBody(required = false) GamerLoopRequest request) {
        GameTarget target;
        try {
            target = resolveTarget(request);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        log.info("[游戏控制器] 暂停游戏循环: game={}, session={}", target.gameName(), target.sessionId());
        gameLoopService.pause(target.gameName(), target.sessionId());

        ActiveGameSession s = gameLoopService.status(target.gameName(), target.sessionId());
        return ResponseEntity.ok(Map.of(
                "message", "游戏循环已暂停",
                "session", s != null ? sessionInfo(s) : Map.of()
        ));
    }

    @PostMapping("/loop/resume")
    public ResponseEntity<?> resumeLoop(@RequestBody(required = false) GamerLoopRequest request) {
        GameTarget target;
        try {
            target = resolveTarget(request);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        log.info("[游戏控制器] 恢复游戏循环: game={}, session={}", target.gameName(), target.sessionId());
        gameLoopService.resume(target.gameName(), target.sessionId());

        ActiveGameSession s = gameLoopService.status(target.gameName(), target.sessionId());
        return ResponseEntity.ok(Map.of(
                "message", "游戏循环已恢复",
                "session", s != null ? sessionInfo(s) : Map.of()
        ));
    }

    @GetMapping("/loop/status")
    public ResponseEntity<?> loopStatus(
            @RequestParam(required = false) String gameName,
            @RequestParam(required = false) String sessionId) {
        GameTarget target;
        try {
            target = resolveTarget(gameName, sessionId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        ActiveGameSession s = gameLoopService.status(target.gameName(), target.sessionId());
        if (s == null) {
            return ResponseEntity.ok(Map.of(
                    "gameName", target.gameName(),
                    "sessionId", target.sessionId(),
                    "running", false
            ));
        }
        return ResponseEntity.ok(Map.of(
                "running", s.getState() == ActiveGameSession.State.RUNNING,
                "session", sessionInfo(s)
        ));
    }

    private GameTarget resolveTarget(GamerLoopRequest request) {
        String gameName = request != null ? request.gameName : null;
        String sessionId = request != null ? request.sessionId : null;
        String rpSessionId = request != null ? request.rpSessionId : null;
        return resolveTarget(gameName, sessionId, rpSessionId);
    }

    private GameTarget resolveTarget(String gameName, String sessionId) {
        return resolveTarget(gameName, sessionId, null);
    }

    /**
     * 解析游戏目标和可选 RP 会话绑定。
     *
     * @param gameName    请求中的游戏名
     * @param sessionId   请求中的游戏侧会话 id
     * @param rpSessionId 请求中的 RP 会话 id
     * @return 已规范化的目标
     */
    private GameTarget resolveTarget(String gameName, String sessionId, String rpSessionId) {
        String resolvedSessionId = requestResolver.resolveSessionId(sessionId);
        return new GameTarget(
                requestResolver.resolveGameName(gameName),
                resolvedSessionId,
                rpSessionId == null || rpSessionId.isBlank() ? resolvedSessionId : rpSessionId.trim()
        );
    }

    private Map<String, Object> sessionInfo(ActiveGameSession s) {
        return Map.of(
                "gameName", s.getGameName(),
                "sessionId", s.getSessionId(),
                "rpSessionId", s.getRpSessionId(),
                "state", s.getState().name(),
                "startedAt", s.getStartedAt().toString(),
                "lastActivityAt", s.getLastActivityAt().toString(),
                "totalStepCount", s.getTotalStepCount()
        );
    }

    public static class GamerPlayRequest {
        public String gameName;
        public String sessionId;
        public String message;
    }

    public static class GamerLoopRequest {
        public String gameName;
        public String sessionId;
        public String rpSessionId;
    }

    private record GameTarget(String gameName, String sessionId, String rpSessionId) {
    }
}
