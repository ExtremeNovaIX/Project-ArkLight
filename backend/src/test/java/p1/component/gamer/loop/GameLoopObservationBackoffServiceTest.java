package p1.component.gamer.loop;

import org.junit.jupiter.api.Test;
import p1.component.agent.gamer.loop.ActiveGameSession;
import p1.component.agent.gamer.loop.GameLoopObservationBackoffService;
import p1.config.mcp.GameLoopProperties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameLoopObservationBackoffServiceTest {

    @Test
    void shouldSkipObservationAfterRepeatedNoEffectiveActions() {
        GameLoopProperties properties = new GameLoopProperties();
        properties.getSlowObserve().setThreshold(1);
        properties.getSlowObserve().setInitialMs(1000);
        ActiveGameSession session = new ActiveGameSession("STS2MCP", "game-session");
        GameLoopObservationBackoffService service = new GameLoopObservationBackoffService();

        service.recordNoEffectiveAction(session, properties, "等待队友行动");

        assertTrue(service.shouldSkip(session, properties));

        service.reset(session);

        assertFalse(service.shouldSkip(session, properties));
    }
}
