package p1.component.gamer.adapter;

import org.junit.jupiter.api.Test;
import p1.component.agent.gamer.adapter.core.GameStateJsonSanitizer;
import p1.component.agent.gamer.adapter.core.GameStateSnapshot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameStateJsonSanitizerTest {

    @Test
    void shouldRemoveCanProceedOnlyFromNestedGameStateJson() throws Exception {
        String rawJson = """
                {
                  "state_type":"shop",
                  "shop":{"can_proceed":false,"items":[{"name":"黑洞","can_afford":true}]},
                  "options":[{"title":"继续","is_proceed":true}],
                  "card_reward":{"can_skip":true}
                }
                """;
        GameStateSnapshot state = new GameStateSnapshot(rawJson, new com.fasterxml.jackson.databind.ObjectMapper().readTree(rawJson), "shop");

        String sanitized = GameStateJsonSanitizer.sanitizeToString(state);

        assertFalse(sanitized.contains("can_proceed"));
        assertTrue(sanitized.contains("can_afford"));
        assertTrue(sanitized.contains("is_proceed"));
        assertTrue(sanitized.contains("can_skip"));
    }
}
