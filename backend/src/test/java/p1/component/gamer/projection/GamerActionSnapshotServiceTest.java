package p1.component.gamer.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import p1.component.agent.gamer.adapter.core.GameOperation;
import p1.component.agent.gamer.projection.GamerActionSnapshotService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GamerActionSnapshotServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldRenderRpSnapshotWithSummaryAndNotesOnly() throws Exception {
        GamerActionSnapshotService snapshots = new GamerActionSnapshotService();

        snapshots.record(
                "STS2MCP",
                "STS2MCP-testa",
                "先补格挡再观察局势",
                List.of(new GameOperation(
                        "combat_play_card",
                        objectMapper.readTree("{\"card\":\"防御\",\"target\":\"self\"}"),
                        "打出防御补上格挡")));

        String snapshot = snapshots.renderForRp("STS2MCP", "STS2MCP-testa");

        assertTrue(snapshot.contains("先补格挡再观察局势"));
        assertTrue(snapshot.contains("打出防御补上格挡"));
        assertFalse(snapshot.contains("combat_play_card"));
        assertFalse(snapshot.contains("\"card\""));
        assertFalse(snapshot.contains("已成功执行"));
    }
}
