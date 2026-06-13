package p1.component.gamer.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import p1.component.agent.gamer.adapter.STS2Adapter;
import p1.component.agent.gamer.adapter.core.GameActionability;
import p1.component.agent.gamer.adapter.core.GameActionabilityStatus;
import p1.component.agent.gamer.adapter.core.GameStateSnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;

class STS2AdapterActionabilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final STS2Adapter adapter = new STS2Adapter();

    @Test
    void shouldWaitDuringEnemyCombatTurn() throws Exception {
        GameActionability result = adapter.evaluateActionability(state("""
                {
                  "state_type": "monster",
                  "battle": {
                    "turn": "enemy",
                    "is_play_phase": false
                  }
                }
                """));

        assertEquals(GameActionabilityStatus.WAITING, result.status());
    }

    @Test
    void shouldActDuringPlayerCombatPlayPhase() throws Exception {
        GameActionability result = adapter.evaluateActionability(state("""
                {
                  "state_type": "monster",
                  "battle": {
                    "turn": "player",
                    "is_play_phase": true
                  }
                }
                """));

        assertEquals(GameActionabilityStatus.ACTIONABLE, result.status());
    }

    @Test
    void shouldActInNonCombatInteractionState() throws Exception {
        GameActionability result = adapter.evaluateActionability(state("""
                {
                  "state_type": "reward",
                  "rewards": []
                }
                """));

        assertEquals(GameActionabilityStatus.ACTIONABLE, result.status());
    }

    @Test
    void shouldStopWhenGameIsOver() throws Exception {
        GameActionability result = adapter.evaluateActionability(state("""
                {
                  "state_type": "game_over"
                }
                """));

        assertEquals(GameActionabilityStatus.GAME_OVER, result.status());
    }

    private GameStateSnapshot state(String raw) throws Exception {
        JsonNode root = objectMapper.readTree(raw);
        return new GameStateSnapshot(raw, root, root.path("state_type").asText(""));
    }
}
