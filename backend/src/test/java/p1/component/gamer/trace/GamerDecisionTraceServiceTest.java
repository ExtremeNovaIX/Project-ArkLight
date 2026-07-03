package p1.component.gamer.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import p1.component.agent.gamer.adapter.core.GameOperation;
import p1.component.agent.gamer.trace.GameOperationExecutionTrace;
import p1.component.agent.gamer.trace.GameQueueExecutionTrace;
import p1.component.agent.gamer.trace.GameStateTraceHasher;
import p1.component.agent.gamer.trace.GamerDecisionTraceService;
import p1.config.mcp.GameTraceProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GamerDecisionTraceServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldRenderParserAndOperationLevelTrace() throws Exception {
        Path traceDir = Path.of("target", "test-game-traces", UUID.randomUUID().toString());
        Files.createDirectories(traceDir);
        GameTraceProperties properties = new GameTraceProperties();
        properties.setTraceEnabled(true);
        properties.setTraceDirectory(traceDir.toString());
        GamerDecisionTraceService traceService = new GamerDecisionTraceService(properties);

        String gameName = "STS2MCP";
        String memoryId = "session-a";

        // 初始化会话
        Path tracePath = traceService.initSession(gameName, memoryId);
        assertTrue(tracePath != null && Files.exists(tracePath), "initSession 应创建 trace 文件");

        // 记录 plan
        String planText = "局面：敌人海洋混混21血\n目标：防守\n路线：中和→防御\n沟通：短句";
        traceService.recordTurnPlan(gameName, memoryId, planText);

        // 构建 execution trace
        String startHash = GameStateTraceHasher.shortHash(state("{\"state_type\":\"monster\"}"));
        GameQueueExecutionTrace executionTrace = new GameQueueExecutionTrace(
                "queue-1",
                "monster",
                startHash,
                "combat|game_mode=multiplayer|battle.round=1|battle.turn=player|battle.is_play_phase=true",
                25,
                List.of(new GameOperationExecutionTrace(
                        1,
                        "combat_play_card",
                        "{\"card\":\"打击\"}",
                        "monster",
                        startHash,
                        "monster",
                        startHash,
                        "combat|game_mode=multiplayer|battle.round=1|battle.turn=player|battle.is_play_phase=true",
                        "combat|game_mode=multiplayer|battle.round=1|battle.turn=player|battle.is_play_phase=true",
                        "play_card card=打击 target=NIBBIT_0",
                        "success",
                        8,
                        "{\"status\":\"ok\"}",
                        "")),
                "");

        // 记录 RP 控制块 (act)，会暂存 check/progress/next/commit
        p1.component.agent.rp.game.control.RpControlBlock block =
                new p1.component.agent.rp.game.control.RpControlBlock(
                        p1.component.agent.rp.game.control.RpControlBlock.Type.ACT,
                        p1.component.agent.rp.game.control.RpControlBlock.VoiceKind.CHAT,
                        "",
                        "打出打击攻击敌方海洋混混",
                        "费用合法",
                        "步骤1防守完成",
                        "步骤2输出",
                        true);
        traceService.appendRpControlBlockTrace(gameName, memoryId, block);

        // 消费暂存数据
        String consumedPlan = traceService.consumePendingPlan(gameName, memoryId);
        GamerDecisionTraceService.ActBlockContext actCtx = traceService.consumePendingActContext(gameName, memoryId);

        traceService.appendQueueTrace(
                gameName,
                memoryId,
                "先打一张打击。",
                "打出打击",
                "{\"operations\":[{\"tool\":\"combat_play_card\"}]}",
                123,
                true,
                true,
                List.of(new GameOperation("combat_play_card", objectMapper.readTree("{\"card\":\"打击\"}"))),
                "已成功执行 1/1 条操作。",
                "",
                executionTrace,
                consumedPlan != null ? consumedPlan : "",
                actCtx != null ? actCtx.check() : "",
                actCtx != null ? actCtx.progress() : "",
                actCtx != null ? actCtx.next() : "",
                actCtx != null && actCtx.commit());

        // 写摘要
        traceService.writeSummary(gameName, memoryId);

        String content = Files.readString(tracePath);

        // frontmatter
        assertTrue(content.contains("game: STS2MCP"), "frontmatter 应包含 game");
        assertTrue(content.contains("session: session-a"), "frontmatter 应包含 session");

        // 摘要
        assertTrue(content.contains("# 会话摘要"), "应包含摘要标题");
        assertTrue(content.contains("决策时间线"), "应包含决策时间线");
        assertTrue(content.contains("统计"), "应包含统计");

        // 局面分析
        assertTrue(content.contains("局面分析"), "应包含局面分析 section");
        assertTrue(content.contains("敌人海洋混混21血"), "应包含 plan 内容");

        // 思维链
        assertTrue(content.contains("先打一张打击"), "应包含 reasoning content");

        // 操作协议
        assertTrue(content.contains("操作协议"), "应包含操作协议 section");
        assertTrue(content.contains("do: 打出打击"), "应包含 do");
        assertTrue(content.contains("check: 费用合法"), "应包含 check");
        assertTrue(content.contains("progress: 步骤1防守完成"), "应包含 progress");
        assertTrue(content.contains("next: 步骤2输出"), "应包含 next");
        assertTrue(content.contains("commit: true"), "应包含 commit");

        // parser
        assertTrue(content.contains("latency_ms=123"), "应包含 parser 延迟");

        // MCP 执行
        assertTrue(content.contains("MCP 执行"), "应包含 MCP 执行 section");
        assertTrue(content.contains("queue_id=queue-1"), "应包含 queue id");
        assertTrue(content.contains("outcome_kind=success") || content.contains("success"),
                "应包含 success outcome");
        assertTrue(content.contains("combat_play_card"), "应包含 tool name");

        // RP 事件
        assertTrue(content.contains("RP 事件"), "应包含 RP 事件 section");
    }

    @Test
    void shouldAttachRpVisibleContextToQueueTrace() throws Exception {
        Path traceDir = Path.of("target", "test-game-traces", UUID.randomUUID().toString());
        Files.createDirectories(traceDir);
        GameTraceProperties properties = new GameTraceProperties();
        properties.setTraceEnabled(true);
        properties.setTraceDirectory(traceDir.toString());
        GamerDecisionTraceService traceService = new GamerDecisionTraceService(properties);

        String gameName = "STS2MCP";
        String memoryId = "session-visible";
        Path tracePath = traceService.initSession(gameName, memoryId);
        String visibleContext = """
                <game_mode>
                <current_game_state>
                <available_actions>
                - 打出手牌
                </available_actions>
                <key_game_state_markdown>
                ## 当前局面
                - state_type：monster
                ## 本步决策重点
                - 敌方即将造成伤害：11
                </key_game_state_markdown>
                </current_game_state>
                </game_mode>
                """;
        traceService.recordRpVisibleContext(gameName, memoryId, visibleContext);

        traceService.appendQueueTrace(
                gameName,
                memoryId,
                "",
                "打出防御",
                "",
                0,
                false,
                false,
                List.of(),
                "已成功执行 1/1 条操作。",
                "",
                null,
                "",
                "",
                "",
                "",
                true);

        String content = Files.readString(tracePath);

        assertTrue(content.contains("RP 可见上下文"), "应记录 RP 当时看到的上下文");
        assertTrue(content.contains("<current_game_state>"), "应包含当前游戏状态上下文");
        assertTrue(content.contains("<available_actions>"), "应包含 RP 可见动作摘要");
        assertTrue(content.contains("<key_game_state_markdown>"), "应包含 RP 可见关键局面 Markdown");
        assertTrue(content.contains("敌方即将造成伤害：11"), "应保留决策关键字段");
        assertTrue(content.contains("执行结果"), "应记录 RP 命令执行结果 section");
        assertTrue(content.contains("已成功执行 1/1 条操作。"), "应记录执行反馈文本");
    }

    @Test
    void shouldRecordRpCommandFailureWithVisibleContext() throws Exception {
        Path traceDir = Path.of("target", "test-game-traces", UUID.randomUUID().toString());
        Files.createDirectories(traceDir);
        GameTraceProperties properties = new GameTraceProperties();
        properties.setTraceEnabled(true);
        properties.setTraceDirectory(traceDir.toString());
        GamerDecisionTraceService traceService = new GamerDecisionTraceService(properties);

        String gameName = "STS2MCP";
        String memoryId = "session-failure";
        Path tracePath = traceService.initSession(gameName, memoryId);
        traceService.recordRpVisibleContext(gameName, memoryId, "<current_game_state><key_game_state_markdown>## 当前局面</key_game_state_markdown></current_game_state>");

        traceService.appendRpCommandFailureTrace(gameName, memoryId, "选择左边事件", "底层执行失败：目标不存在");

        String content = Files.readString(tracePath);

        assertTrue(content.contains("rp_command_failure"), "应记录 RP 命令失败 step");
        assertTrue(content.contains("RP 可见上下文"), "异常也应带上 RP 当时看到的上下文");
        assertTrue(content.contains("<key_game_state_markdown>"), "异常 trace 应保留关键局面 Markdown");
        assertTrue(content.contains("选择左边事件"), "应记录失败的 RP 命令");
        assertTrue(content.contains("执行异常"), "应记录执行异常 section");
        assertTrue(content.contains("底层执行失败：目标不存在"), "应记录异常反馈");
    }

    @Test
    void shouldConsumePendingRpReasoning() {
        GameTraceProperties properties = new GameTraceProperties();
        properties.setTraceEnabled(true);
        GamerDecisionTraceService traceService = new GamerDecisionTraceService(properties);

        traceService.recordTurnReasoning("STS2MCP", "game-session", "hidden reasoning");

        org.junit.jupiter.api.Assertions.assertEquals(
                "hidden reasoning",
                traceService.consumePendingReasoning("STS2MCP", "game-session"));
    }
    private p1.component.agent.gamer.adapter.core.GameStateSnapshot state(String raw) throws Exception {
        return new p1.component.agent.gamer.adapter.core.GameStateSnapshot(
                raw,
                objectMapper.readTree(raw),
                objectMapper.readTree(raw).path("state_type").asText(""));
    }
}
