package p1.component.gamer.adapter;

import org.junit.jupiter.api.Test;
import p1.component.agent.gamer.adapter.core.GameActionability;
import p1.component.agent.gamer.adapter.core.GameActionabilityStatus;
import p1.component.agent.gamer.adapter.STS2Adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

class STS2AdapterActionabilityTest {

    private final STS2Adapter adapter = new STS2Adapter();

    @Test
    void shouldWaitDuringEnemyCombatTurn() {
        GameActionability result = adapter.evaluateActionability(adapter.parseState("""
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
    void shouldActDuringPlayerCombatPlayPhase() {
        GameActionability result = adapter.evaluateActionability(adapter.parseState("""
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
    void shouldActInNonCombatInteractionState() {
        GameActionability result = adapter.evaluateActionability(adapter.parseState("""
                {
                  "state_type": "reward",
                  "rewards": []
                }
                """));

        assertEquals(GameActionabilityStatus.ACTIONABLE, result.status());
    }

    @Test
    void shouldStopWhenGameIsOver() {
        GameActionability result = adapter.evaluateActionability(adapter.parseState("""
                {
                  "state_type": "game_over"
                }
                """));

        assertEquals(GameActionabilityStatus.GAME_OVER, result.status());
    }
}
