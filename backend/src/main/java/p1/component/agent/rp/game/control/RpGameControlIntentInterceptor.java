package p1.component.agent.rp.game.control;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import p1.component.agent.interaction.GameCoordinationService;
import p1.component.agent.router.InstructionRouteDecision;
import p1.component.agent.router.InstructionRouteRequest;
import p1.component.agent.router.InstructionRouter;
import p1.component.agent.router.InstructionRouterModelConfig;
import p1.component.agent.router.InstructionRouterTask;
import p1.component.agent.router.InstructionRouterTaskRegistry;
import p1.config.prop.AssistantProperties;

import java.util.List;
import java.util.Optional;

import static p1.utils.SessionUtil.normalizeSessionId;

/**
 * RP 游戏模式下的用户控制意图拦截器。
 * <p>
 * RP 配置启用指令路由时，每条用户消息都会先交给通用路由器判断；游戏模式下会消费 WAIT、READY、
 * APPLY_INSTRUCTION 等控制结果。未启用路由时，保留少量高置信度规则兜底，避免旧流程失去"等等"能力。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RpGameControlIntentInterceptor {

    private static final String INTENT_WAIT = "WAIT";
    private static final String INTENT_READY = "READY";
    private static final String INTENT_APPLY_INSTRUCTION = "APPLY_INSTRUCTION";
    private static final String INTENT_CHAT = "CHAT";

    static final String TASK_GAME_CONTROL = "rp-game-control";
    static final String TASK_CHAT = "rp-chat";

    private static final List<String> GAME_CONTROL_INTENTS = List.of(
            INTENT_WAIT, INTENT_READY, INTENT_APPLY_INSTRUCTION, INTENT_CHAT);

    private static final List<String> WAIT_PATTERNS = List.of(
            "等等", "等一下", "先等", "先停", "停一下", "暂停", "先别动", "别动", "我看一下", "我看看", "慢点");

    private static final List<String> WAIT_NEGATIONS = List.of(
            "不要停", "别停", "不用停", "不要等", "不用等", "别等");

    private static final List<String> READY_PATTERNS = List.of(
            "好了", "好啦", "可以了", "继续", "接着", "走吧", "不用等了", "别等了");

    private static final List<String> READY_NEGATIONS = List.of(
            "不要继续", "别继续", "先别继续", "不能继续");

    private final GameCoordinationService coordinationService;
    private final InstructionRouter instructionRouter;
    private final InstructionRouterModelConfig routerModelConfig;
    private final InstructionRouterTaskRegistry taskRegistry;
    private final AssistantProperties assistantProperties;

    @PostConstruct
    public void registerTasks() {
        taskRegistry.register(new InstructionRouterTask(
                TASK_GAME_CONTROL,
                GAME_CONTROL_INTENTS,
                gameControlSceneInstruction()));

        taskRegistry.register(new InstructionRouterTask(
                TASK_CHAT,
                List.of(INTENT_CHAT),
                "当前是普通 RP 对话，没有活动游戏会话。本场景只做用户消息路由观测，不产生业务动作。不确定时输出 CHAT。"));
    }

    /**
     * 尝试在 RP 回复前登记用户的明确游戏控制意图。
     *
     * @param rpSessionId RP 会话 id
     * @param userMessage 当前用户输入
     * @return 已处理的控制意图；没有命中时为空
     */
    public Optional<HandledGameControlIntent> intercept(String rpSessionId, String userMessage) {
        String sessionId = normalizeSessionId(rpSessionId);
        String normalizedMessage = normalizeMessage(userMessage);
        if (normalizedMessage.isBlank()) {
            return Optional.empty();
        }

        boolean activeGame = coordinationService.hasActiveGame(sessionId);
        if (assistantProperties.getRp().isInstructionRouterEnabled()) {
            RouterOutcome routed = interceptByInstructionRouter(sessionId, userMessage, activeGame);
            if (routed.available()) {
                return routed.handled();
            }
            return Optional.empty();
        }

        if (activeGame && isWaitIntent(normalizedMessage)) {
            String reason = "用户要求等待：" + userMessage.trim();
            String result = coordinationService.waitForUser(sessionId, 0, reason);
            log.info("[RP游戏控制兜底] 已根据用户文本登记等待: session={}, message={}", sessionId, userMessage);
            return Optional.of(new HandledGameControlIntent("WAIT", result));
        }

        if (activeGame && coordinationService.isWaiting(sessionId) && isReadyIntent(normalizedMessage)) {
            String result = coordinationService.markReady(sessionId);
            log.info("[RP游戏控制兜底] 已根据用户文本确认继续: session={}, message={}", sessionId, userMessage);
            return Optional.of(new HandledGameControlIntent("READY", result));
        }

        return Optional.empty();
    }

    private RouterOutcome interceptByInstructionRouter(String sessionId, String userMessage, boolean activeGame) {
        InstructionRouteDecision decision = instructionRouter.route(buildRouteRequest(sessionId, userMessage, activeGame));
        if (!decision.available()) {
            return new RouterOutcome(false, Optional.empty());
        }
        if (!decision.confidentEnough(routerModelConfig.minConfidence())) {
            log.debug("[RP游戏控制路由] 低置信度，忽略: session={}, intent={}, confidence={}",
                    sessionId, decision.intent(), decision.confidence());
            return new RouterOutcome(true, Optional.empty());
        }

        if (!activeGame) {
            return new RouterOutcome(true, Optional.empty());
        }
        return new RouterOutcome(true, handleGameRouterDecision(sessionId, userMessage, decision));
    }

    private InstructionRouteRequest buildRouteRequest(String sessionId, String userMessage, boolean activeGame) {
        String taskId = activeGame ? TASK_GAME_CONTROL : TASK_CHAT;
        return InstructionRouteRequest.byTask(
                taskId,
                taskId,
                "rpSession=" + sessionId
                        + "\nactiveGame=" + activeGame
                        + "\nwaiting=" + (activeGame && coordinationService.isWaiting(sessionId)),
                userMessage);
    }

    private String gameControlSceneInstruction() {
        return """
                <intent_map>
                WAIT = 暂停/等待/观察局面，不含具体游戏动作
                READY = 确认继续/取消等待
                APPLY_INSTRUCTION = 给出具体游戏动作（结束回合/出牌/跳过/使用技能等）
                CHAT = 聊天/提问/建议/复盘，不改变游戏节奏
                </intent_map>

                优先级：APPLY_INSTRUCTION > READY > WAIT > CHAT
                有具体游戏动作 → APPLY_INSTRUCTION，绝对不是 WAIT。

                <examples>
                <example>
                <user>等等，我先看看局面</user>
                <output>{"intent": "WAIT", "confidence": 0.95, "instruction": ""}</output>
                </example>
                <example>
                <user>先停一下</user>
                <output>{"intent": "WAIT", "confidence": 0.95, "instruction": ""}</output>
                </example>
                <example>
                <user>好了继续吧</user>
                <output>{"intent": "READY", "confidence": 0.9, "instruction": ""}</output>
                </example>
                <example>
                <user>别等了，不用停</user>
                <output>{"intent": "READY", "confidence": 0.9, "instruction": ""}</output>
                </example>
                <example>
                <user>直接结束回合</user>
                <output>{"intent": "APPLY_INSTRUCTION", "confidence": 0.95, "instruction": "结束回合"}</output>
                </example>
                <example>
                <user>给我出两张防御</user>
                <output>{"intent": "APPLY_INSTRUCTION", "confidence": 0.95, "instruction": "出两张防御"}</output>
                </example>
                <example>
                <user>为什么这么打</user>
                <output>{"intent": "CHAT", "confidence": 0.9, "instruction": ""}</output>
                </example>
                <example>
                <user>现在局面怎么样</user>
                <output>{"intent": "CHAT", "confidence": 0.9, "instruction": ""}</output>
                </example>
                </examples>

                APPLY_INSTRUCTION 的 instruction 只保留用户明确说出的动作，不要补充细节。
                如果运行时 waiting=false 且用户只说「继续/好了」没有新动作，输出 CHAT。
                不确定时输出 CHAT。
                """;
    }

    private Optional<HandledGameControlIntent> handleGameRouterDecision(String sessionId,
                                                                        String userMessage,
                                                                        InstructionRouteDecision decision) {
        String intent = decision.normalizedIntent();
        return switch (intent) {
            case INTENT_WAIT -> {
                String reason = "用户要求等待：" + userMessage.trim();
                String result = coordinationService.waitForUser(sessionId, 0, reason);
                log.info("[RP游戏控制路由] 已登记等待: session={}, confidence={}",
                        sessionId, decision.confidence());
                yield Optional.of(new HandledGameControlIntent(INTENT_WAIT, result));
            }
            case INTENT_READY -> {
                if (!coordinationService.isWaiting(sessionId)) {
                    yield Optional.empty();
                }
                String result = coordinationService.markReady(sessionId);
                log.info("[RP游戏控制路由] 已确认继续: session={}, confidence={}",
                        sessionId, decision.confidence());
                yield Optional.of(new HandledGameControlIntent(INTENT_READY, result));
            }
            case INTENT_APPLY_INSTRUCTION -> {
                String instruction = decision.instruction() == null || decision.instruction().isBlank()
                        ? userMessage.trim()
                        : decision.instruction().trim();
                String result = coordinationService.applyInstruction(sessionId, instruction);
                log.info("[RP游戏控制路由] 已转发游戏指令: session={}, confidence={}, instruction={}",
                        sessionId, decision.confidence(), decision.instruction());
                yield Optional.of(new HandledGameControlIntent(INTENT_APPLY_INSTRUCTION, result));
            }
            default -> Optional.empty();
        };
    }

    private boolean isWaitIntent(String normalizedMessage) {
        if (containsAny(normalizedMessage, WAIT_NEGATIONS)) {
            return false;
        }
        return containsAny(normalizedMessage, WAIT_PATTERNS);
    }

    private boolean isReadyIntent(String normalizedMessage) {
        if (containsAny(normalizedMessage, READY_NEGATIONS)) {
            return false;
        }
        return containsAny(normalizedMessage, READY_PATTERNS);
    }

    private String normalizeMessage(String message) {
        if (message == null) {
            return "";
        }
        return message.toLowerCase()
                .replace(" ", "")
                .replace("\t", "")
                .trim();
    }

    private boolean containsAny(String text, List<String> patterns) {
        return patterns.stream().anyMatch(text::contains);
    }

    public record HandledGameControlIntent(String action, String result) {
    }

    private record RouterOutcome(boolean available, Optional<HandledGameControlIntent> handled) {
    }
}
