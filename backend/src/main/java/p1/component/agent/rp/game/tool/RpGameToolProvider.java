package p1.component.agent.rp.game.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import p1.component.agent.gamer.interrupt.GameInterruptService;
import p1.component.agent.gamer.loop.ActiveGameRegistry;
import p1.component.agent.gamer.loop.ActiveGameSession;
import p1.utils.SessionUtil;

import java.util.List;
import java.util.Optional;

/**
 * RP 游戏模式动态工具提供器。
 * <p>
 * 只有当前 RP 会话存在活跃游戏循环时，才向 RP 暴露 game_control 工具；
 * 这样普通 RP 对话不会看到游戏工具，也不会意外触发游戏控制逻辑。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RpGameToolProvider implements ToolProvider {

    public static final String GAME_CONTROL_TOOL = "game_control";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ActiveGameRegistry activeGameRegistry;
    private final GameInterruptService interruptService;

    /**
     * 按 RP 会话动态提供游戏工具。
     *
     * @param request LangChain4j 工具查询上下文
     * @return 游戏模式下包含 game_control；非游戏模式下为空
     */
    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        String sessionId = request == null ? "" : normalizeMemoryId(request.chatMemoryId());
        if (activeGameRegistry.findBySessionId(sessionId).isEmpty()) {
            return ToolProviderResult.builder().build();
        }

        return ToolProviderResult.builder()
                .add(gameControlToolSpec(), (toolRequest, memoryId) ->
                        executeGameControl(normalizeMemoryId(memoryId), toolRequest.arguments()))
                .build();
    }

    /**
     * 执行 RP 提交的游戏控制指令。
     *
     * @param sessionId    RP 会话 id
     * @param rawArguments 工具参数 JSON
     * @return 返回给 RP 的执行反馈
     */
    private String executeGameControl(String sessionId, String rawArguments) {
        Optional<ActiveGameSession> activeSession = activeGameRegistry.findBySessionId(sessionId);
        if (activeSession.isEmpty()) {
            return "当前没有可控制的游戏会话。";
        }

        ActiveGameSession session = activeSession.get();
        GameControlArguments args = parseArguments(rawArguments);
        String instruction = args.instruction().isBlank()
                ? defaultInstruction(args.action())
                : args.instruction();

        switch (args.action()) {
            case "PAUSE" -> {
                interruptService.requestInterrupt(session.getGameName(), session.getSessionId(), "rp", instruction);
                session.setState(ActiveGameSession.State.PAUSED);
                log.info("[RP游戏工具] 已暂停游戏循环: game={}, session={}, instruction={}",
                        session.getGameName(), session.getSessionId(), instruction);
                return "已暂停当前游戏操作，并记录新的游戏意图：" + instruction;
            }
            case "RESUME" -> {
                session.setState(ActiveGameSession.State.RUNNING);
                interruptService.requestInterrupt(session.getGameName(), session.getSessionId(), "rp", instruction);
                log.info("[RP游戏工具] 已恢复游戏循环: game={}, session={}, instruction={}",
                        session.getGameName(), session.getSessionId(), instruction);
                return "已恢复游戏操作，并会按新的游戏意图继续：" + instruction;
            }
            default -> {
                boolean resumedFromPause = session.getState() == ActiveGameSession.State.PAUSED;
                if (resumedFromPause) {
                    session.setState(ActiveGameSession.State.RUNNING);
                }
                interruptService.requestInterrupt(session.getGameName(), session.getSessionId(), "rp", instruction);
                log.info("[RP游戏工具] 已提交用户游戏指令: game={}, session={}, resumedFromPause={}, instruction={}",
                        session.getGameName(), session.getSessionId(), resumedFromPause, instruction);
                if (resumedFromPause) {
                    return "已恢复当前游戏操作，并按用户新的游戏指令重新决策：" + instruction;
                }
                return "已收到用户新的游戏指令，会打断未执行操作并基于最新状态重新决策：" + instruction;
            }
        }
    }

    /**
     * 构建 RP 可调用的游戏控制工具规格。
     *
     * @return LangChain4j 工具定义
     */
    private ToolSpecification gameControlToolSpec() {
        JsonObjectSchema parameters = JsonObjectSchema.builder()
                .description("只转发用户明确提出的游戏控制意图。普通对话、RP 自己的战术分析、感叹和复盘不要调用。")
                .addEnumProperty("action", List.of("APPLY_INSTRUCTION", "PAUSE", "RESUME"),
                        "APPLY_INSTRUCTION=提交用户新的游戏指令并重规划，暂停态下自动恢复；PAUSE=暂停自动操作；RESUME=没有新指令时恢复自动操作")
                .addStringProperty("instruction", "用户明确提出的新游戏意图。用户已经给出具体动作时要保留，例如“本回合直接结束回合”；不要自行补写牌序、目标索引或不存在的战术细节。")
                .required("action")
                .additionalProperties(false)
                .build();

        return ToolSpecification.builder()
                .name(GAME_CONTROL_TOOL)
                .description("""
                        当前对话处于游戏模式时，用于改变、打断、暂停或恢复接下来的游戏操作。
                        只有用户明确要求你改变接下来的游戏行为时才调用；不要因为你看懂了当前局势就自己调用。
                        游戏语境中的问句也可能是明确指令。例如用户说“你可以直接结束回合吗？”，应调用 APPLY_INSTRUCTION 并保留“直接结束回合”，不能只口头答应。
                        用户只说“继续”“你接着玩”时调用 RESUME；用户携带了新的动作、目标或约束时调用 APPLY_INSTRUCTION。
                        调用后系统会在安全边界丢弃未执行的旧操作，并让下一次游戏决策基于最新状态和 instruction 重新规划。
                        """)
                .parameters(parameters)
                .build();
    }

    /**
     * 解析工具参数并做兜底。
     *
     * @param rawArguments 原始 JSON 参数
     * @return 规范化后的控制参数
     */
    private GameControlArguments parseArguments(String rawArguments) {
        try {
            JsonNode root = objectMapper.readTree(rawArguments == null || rawArguments.isBlank() ? "{}" : rawArguments);
            String action = root.path("action").asText("APPLY_INSTRUCTION").trim().toUpperCase();
            if ("INTERRUPT".equals(action)) {
                // 兼容旧工具动作，内部统一按用户明确指令处理。
                action = "APPLY_INSTRUCTION";
            }
            if (!List.of("APPLY_INSTRUCTION", "PAUSE", "RESUME").contains(action)) {
                action = "APPLY_INSTRUCTION";
            }
            String instruction = root.path("instruction").asText("").trim();
            return new GameControlArguments(action, instruction);
        } catch (Exception e) {
            log.warn("[RP游戏工具] game_control 参数解析失败，按用户游戏指令处理: arguments={}, reason={}",
                    rawArguments, e.getMessage());
            return new GameControlArguments("APPLY_INSTRUCTION", rawArguments == null ? "" : rawArguments.trim());
        }
    }

    /**
     * 没有显式 instruction 时按控制动作生成默认意图。
     *
     * @param action 控制动作
     * @return 默认游戏意图
     */
    private String defaultInstruction(String action) {
        return switch (action) {
            case "PAUSE" -> "暂停当前游戏操作，等待用户进一步指令。";
            case "RESUME" -> "恢复游戏操作，基于最新状态继续推进。";
            default -> "按用户最新游戏指令打断当前未执行操作，并基于最新状态重新决策。";
        };
    }

    /**
     * 规范化 LangChain4j 传入的 memoryId。
     *
     * @param memoryId 原始 memoryId
     * @return RP 会话 id
     */
    private String normalizeMemoryId(Object memoryId) {
        if (memoryId == null) {
            return "";
        }
        return SessionUtil.normalizeSessionId(memoryId.toString());
    }

    /**
     * 工具参数对象。
     *
     * @param action      控制动作
     * @param instruction 新的游戏行动意图
     */
    private record GameControlArguments(String action, String instruction) {
    }
}
