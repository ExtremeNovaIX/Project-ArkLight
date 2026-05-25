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
import p1.component.agent.gamer.loop.ActiveGameRegistry;
import p1.component.agent.interaction.GameCoordinationService;
import p1.utils.SessionUtil;

import java.util.List;

/**
 * RP 游戏模式动态工具提供器。
 * <p>
 * 只有当前 RP 会话存在活跃游戏循环时，才向 RP 暴露 game_coordination 工具；
 * 这样普通 RP 对话不会看到游戏工具，也不会意外触发游戏控制逻辑。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RpGameToolProvider implements ToolProvider {

    public static final String GAME_COORDINATION_TOOL = "game_coordination";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ActiveGameRegistry activeGameRegistry;
    private final GameCoordinationService coordinationService;

    /**
     * 按 RP 会话动态提供游戏工具。
     *
     * @param request LangChain4j 工具查询上下文
     * @return 游戏模式下包含 game_coordination；非游戏模式下为空
     */
    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        String sessionId = request == null ? "" : normalizeMemoryId(request.chatMemoryId());
        if (activeGameRegistry.findBySessionId(sessionId).isEmpty()) {
            return ToolProviderResult.builder().build();
        }

        return ToolProviderResult.builder()
                .add(gameCoordinationToolSpec(), (toolRequest, memoryId) ->
                        executeGameCoordination(normalizeMemoryId(memoryId), toolRequest.arguments()))
                .build();
    }

    /**
     * 执行 RP 提交的游戏协调指令。
     *
     * @param sessionId    RP 会话 id
     * @param rawArguments 工具参数 JSON
     * @return 返回给 RP 的执行反馈
     */
    private String executeGameCoordination(String sessionId, String rawArguments) {
        if (!coordinationService.hasActiveGame(sessionId)) {
            return "当前没有可控制的游戏会话。";
        }

        GameCoordinationArguments args = parseArguments(rawArguments);
        String instruction = args.instruction().trim();

        return switch (args.action()) {
            case "WAIT" -> {
                String reason = instruction.isBlank() ? "用户要求先等一下。" : instruction;
                String result = coordinationService.waitForUser(sessionId, args.durationSeconds(), reason);
                log.info("[RP游戏工具] 已登记等待: session={}, durationSeconds={}, reason={}",
                        sessionId, args.durationSeconds(), reason);
                yield result;
            }
            case "READY" -> {
                String result = coordinationService.markReady(sessionId);
                log.info("[RP游戏工具] 已确认继续: session={}", sessionId);
                yield result;
            }
            default -> {
                String normalizedInstruction = instruction.isBlank()
                        ? "用户确认继续，基于最新状态重新决策。"
                        : instruction;
                String result = coordinationService.applyInstruction(sessionId, normalizedInstruction);
                log.info("[RP游戏工具] 已转发用户游戏指令: session={}, instruction={}",
                        sessionId, normalizedInstruction);
                yield result;
            }
        };
    }

    /**
     * 构建 RP 可调用的游戏协调工具规格。
     *
     * @return LangChain4j 工具定义
     */
    private ToolSpecification gameCoordinationToolSpec() {
        JsonObjectSchema parameters = JsonObjectSchema.builder()
                .description("只登记用户明确提出的游戏协调意图。普通对话、RP 自己的战术分析、感叹和复盘不要调用。")
                .addEnumProperty("action", List.of("WAIT", "READY", "APPLY_INSTRUCTION"),
                        "WAIT=用户要求先等一下；READY=用户确认好了/可以继续；APPLY_INSTRUCTION=用户给出新的游戏意图并要求重规划")
                .addIntegerProperty("duration_seconds", "WAIT 后多少秒询问用户是否继续；缺省时使用系统默认值。")
                .addStringProperty("instruction", "用户明确提出的新游戏意图或等待原因。用户已经给出具体动作时要保留，例如“本回合直接结束回合”；不要自行补写牌序、目标索引或不存在的战术细节。")
                .required("action")
                .additionalProperties(false)
                .build();

        return ToolSpecification.builder()
                .name(GAME_COORDINATION_TOOL)
                .description("""
                        当前对话处于游戏模式时，用于登记用户对接下来游戏节奏的明确协调意图。
                        只有用户明确要求你改变接下来的游戏行为时才调用；不要因为你看懂了当前局势就自己调用。
                        用户说“等一下”“等等”“先别动”“我看一下”时调用 WAIT；WAIT 只让系统等待并到点询问，不要口头答应后继续推进。
                        等待期间用户说“好了”“继续”“可以了”时调用 READY；如果同时带新动作，则调用 APPLY_INSTRUCTION。
                        游戏语境中的问句也可能是明确指令。例如用户说“你可以直接结束回合吗？”，应调用 APPLY_INSTRUCTION 并保留“直接结束回合”，不能只口头答应。
                        APPLY_INSTRUCTION 会在安全边界丢弃未执行的旧操作，并让下一次游戏决策基于最新状态和 instruction 重新规划。
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
    private GameCoordinationArguments parseArguments(String rawArguments) {
        try {
            JsonNode root = objectMapper.readTree(rawArguments == null || rawArguments.isBlank() ? "{}" : rawArguments);
            String action = root.path("action").asText("APPLY_INSTRUCTION").trim().toUpperCase();
            if ("INTERRUPT".equals(action)) {
                action = "APPLY_INSTRUCTION";
            }
            if ("PAUSE".equals(action)) {
                // 兼容旧动作：暂停类意图改为等待确认，不再直接切换 gamer 生命周期。
                action = "WAIT";
            }
            if ("RESUME".equals(action)) {
                // 兼容旧动作：恢复类意图改为用户确认继续。
                action = "READY";
            }
            if (!List.of("WAIT", "READY", "APPLY_INSTRUCTION").contains(action)) {
                action = "APPLY_INSTRUCTION";
            }
            String instruction = root.path("instruction").asText("").trim();
            long durationSeconds = root.path("duration_seconds").asLong(root.path("durationSeconds").asLong(0));
            return new GameCoordinationArguments(action, instruction, durationSeconds);
        } catch (Exception e) {
            log.warn("[RP游戏工具] game_coordination 参数解析失败，按用户游戏指令处理: arguments={}, reason={}",
                    rawArguments, e.getMessage());
            return new GameCoordinationArguments("APPLY_INSTRUCTION", rawArguments == null ? "" : rawArguments.trim(), 0);
        }
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
     * @param action          协调动作
     * @param instruction     新的游戏行动意图或等待原因
     * @param durationSeconds 等待后提醒秒数
     */
    private record GameCoordinationArguments(String action, String instruction, long durationSeconds) {
    }
}
