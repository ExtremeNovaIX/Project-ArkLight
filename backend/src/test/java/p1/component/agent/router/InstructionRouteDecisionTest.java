package p1.component.agent.router;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InstructionRouteDecisionTest {

    @Test
    void shouldCreateChatDecision() {
        InstructionRouteDecision decision = InstructionRouteDecision.chat("empty message");

        assertTrue(decision.available());
        assertEquals("CHAT", decision.intent());
        assertEquals(1.0, decision.confidence());
        assertEquals("", decision.instruction());
        assertEquals("", decision.rawResponse());
    }

    @Test
    void shouldCreateUnavailableDecision() {
        InstructionRouteDecision decision = InstructionRouteDecision.unavailable("timeout");

        assertFalse(decision.available());
        assertEquals("UNAVAILABLE", decision.intent());
        assertEquals(0.0, decision.confidence());
        assertEquals("", decision.rawResponse());
    }

    @Test
    void shouldBeConfidentEnoughWhenAboveThreshold() {
        InstructionRouteDecision decision = new InstructionRouteDecision(
                true, "WAIT", 0.8, "", "");

        assertTrue(decision.confidentEnough(0.65));
    }

    @Test
    void shouldNotBeConfidentEnoughWhenBelowThreshold() {
        InstructionRouteDecision decision = new InstructionRouteDecision(
                true, "WAIT", 0.5, "", "");

        assertFalse(decision.confidentEnough(0.65));
    }

    @Test
    void shouldNotBeConfidentEnoughWhenUnavailable() {
        InstructionRouteDecision decision = InstructionRouteDecision.unavailable("down");

        assertFalse(decision.confidentEnough(0.65));
    }

    @Test
    void shouldNormalizeIntent() {
        InstructionRouteDecision decision = new InstructionRouteDecision(
                true, "  wait  ", 0.9, "", "");

        assertEquals("WAIT", decision.normalizedIntent());
    }

    @Test
    void shouldNormalizeNullIntentToEmpty() {
        InstructionRouteDecision decision = new InstructionRouteDecision(
                true, null, 0.9, "", "");

        assertEquals("", decision.normalizedIntent());
    }
}
