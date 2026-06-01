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

        traceService.appendQueueTrace(
                "STS2MCP",
                "session-a",
                "先打一张打击。",
                "打出打击",
                "{\"operations\":[{\"tool\":\"combat_play_card\"}]}",
                123,
                true,
                true,
                List.of(new GameOperation("combat_play_card", objectMapper.readTree("{\"card\":\"打击\"}"))),
                "已成功执行 1/1 条操作。",
                "",
                executionTrace);

        String content = Files.readString(traceDir.resolve(Path.of("STS2MCP", "session-a.md")));

        assertTrue(content.contains("latency_ms=123"));
        assertTrue(content.contains("early_completed=true"));
        assertTrue(content.contains("queue_id=queue-1"));
        assertTrue(content.contains("monster@" + startHash));
        assertTrue(content.contains("start_action_window=combat|game_mode=multiplayer"));
        assertTrue(content.contains("planned_window=combat|game_mode=multiplayer"));
        assertTrue(content.contains("precondition=play_card card=打击 target=NIBBIT_0"));
        assertTrue(content.contains("outcome=success"));
        assertTrue(content.contains("tool=combat_play_card"));
        assertTrue(content.contains("args={\"card\":\"打击\"}"));
    }

    private p1.component.agent.gamer.adapter.core.GameStateSnapshot state(String raw) throws Exception {
        return new p1.component.agent.gamer.adapter.core.GameStateSnapshot(
                raw,
                objectMapper.readTree(raw),
                objectMapper.readTree(raw).path("state_type").asText(""));
    }
}
