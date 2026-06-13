package p1.component.agent.stt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import p1.component.agent.interaction.GameCoordinationService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SttGameIntentGateTest {

    private GameCoordinationService coordinationService;
    private MutableClock clock;
    private SttGameIntentGate gate;

    @BeforeEach
    void setUp() {
        coordinationService = mock(GameCoordinationService.class);
        when(coordinationService.hasActiveGame("rp-1")).thenReturn(true);
        when(coordinationService.beginVoiceInputHold("rp-1", Duration.ofSeconds(12))).thenReturn(true);
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        gate = new SttGameIntentGate(coordinationService, clock);
    }

    @Test
    void partialRequestShouldOnlyOpenVoiceHold() {
        SttGameIntentGate.Result result = gate.onPartial("ws-1", "rp-1", "你 能 不 能 先看一下？");

        assertTrue(result.consumed());
        assertEquals(SttGameIntentGate.INTENT_VOICE_HOLD, result.intent());
        assertTrue(result.triggered());
        verify(coordinationService).beginVoiceInputHold("rp-1", Duration.ofSeconds(12));
        verify(coordinationService, never()).applyInstruction("rp-1", "你 能 不 能 先看一下？");
        verify(coordinationService, never()).waitForUser("rp-1", 0, "你 能 不 能 先看一下？");
    }

    @Test
    void partialQuestionShouldOnlyOpenVoiceHold() {
        SttGameIntentGate.Result result = gate.onPartial("ws-1", "rp-1", "可以先打这个吗");

        assertTrue(result.consumed());
        assertEquals(SttGameIntentGate.INTENT_VOICE_HOLD, result.intent());
        verify(coordinationService).beginVoiceInputHold("rp-1", Duration.ofSeconds(12));
        verify(coordinationService, never()).applyInstruction("rp-1", "可以先打这个吗");
    }

    @Test
    void partialPauseShouldOnlyOpenVoiceHold() {
        SttGameIntentGate.Result result = gate.onPartial("ws-1", "rp-1", "等一下");

        assertTrue(result.consumed());
        assertEquals(SttGameIntentGate.INTENT_VOICE_HOLD, result.intent());
        verify(coordinationService).beginVoiceInputHold("rp-1", Duration.ofSeconds(12));
    }

    @Test
    void partialChatShouldNotBlockGame() {
        SttGameIntentGate.Result result = gate.onPartial("ws-1", "rp-1", "这个画面有点奇怪");

        assertFalse(result.consumed());
        assertEquals("partial-no-match", result.reason());
        verify(coordinationService, never()).beginVoiceInputHold("rp-1", Duration.ofSeconds(12));
    }

    @Test
    void finalPauseShouldBecomeGameWait() {
        SttGameIntentGate.Result result = gate.onFinal("ws-1", "rp-1", "先别动，等一下");

        assertTrue(result.consumed());
        assertTrue(result.triggered());
        assertEquals(SttGameIntentGate.INTENT_WAIT, result.intent());
        verify(coordinationService).waitForUser("rp-1", 0, "先别动，等一下");
    }

    @Test
    void finalReadyShouldOnlyContinueWhenGameIsWaiting() {
        when(coordinationService.isWaiting("rp-1")).thenReturn(false);

        SttGameIntentGate.Result ignored = gate.onFinal("ws-1", "rp-1", "好了继续");

        assertFalse(ignored.consumed());
        verify(coordinationService, never()).markReady("rp-1");

        when(coordinationService.isWaiting("rp-1")).thenReturn(true);

        SttGameIntentGate.Result consumed = gate.onFinal("ws-1", "rp-1", "好了继续");

        assertTrue(consumed.consumed());
        assertEquals(SttGameIntentGate.INTENT_READY, consumed.intent());
        verify(coordinationService).markReady("rp-1");
    }

    @Test
    void finalRequestShouldApplyCompleteFinalText() {
        SttGameIntentGate.Result result = gate.onFinal("ws-1", "rp-1", "你能不能先别贪，往后撤一下？");

        assertTrue(result.consumed());
        assertEquals(SttGameIntentGate.INTENT_APPLY_INSTRUCTION, result.intent());
        verify(coordinationService).applyInstruction("rp-1", "你能不能先别贪，往后撤一下？");
    }

    @Test
    void partialFalseHitShouldReleaseHoldWhenFinalDoesNotMatch() {
        SttGameIntentGate.Result partial = gate.onPartial("ws-1", "rp-1", "可以吗");
        SttGameIntentGate.Result finished = gate.onFinal("ws-1", "rp-1", "可以说这个画面挺乱");

        assertTrue(partial.consumed());
        assertFalse(finished.consumed());
        verify(coordinationService).beginVoiceInputHold("rp-1", Duration.ofSeconds(12));
        verify(coordinationService).endVoiceInputHold("rp-1");
        verify(coordinationService, never()).applyInstruction("rp-1", "可以说这个画面挺乱");
        verify(coordinationService, never()).waitForUser("rp-1", 0, "可以说这个画面挺乱");
    }

    @Test
    void repeatedPartialShouldNotRepeatHoldSideEffectInsideCooldown() {
        SttGameIntentGate.Result first = gate.onPartial("ws-1", "rp-1", "你能不能");
        SttGameIntentGate.Result repeated = gate.onPartial("ws-1", "rp-1", "你能不能");

        assertTrue(first.triggered());
        assertFalse(repeated.triggered());
        assertTrue(repeated.consumed());
        verify(coordinationService, times(1)).beginVoiceInputHold("rp-1", Duration.ofSeconds(12));
    }

    @Test
    void cleanupShouldReleaseActiveVoiceHold() {
        gate.onPartial("ws-1", "rp-1", "等一下");

        gate.cleanup("ws-1");

        verify(coordinationService).endVoiceInputHold("rp-1");
    }

    @Test
    void dryRunShouldMatchWithoutSideEffectsOrActiveGameCheck() {
        when(coordinationService.hasActiveGame("rp-1")).thenReturn(false);

        SttGameIntentGate.Result result = gate.onFinalDryRun("ws-1", "rp-1", "帮我先防守一下");

        assertTrue(result.consumed());
        assertFalse(result.triggered());
        assertEquals("dry-run-would-trigger", result.reason());
        verify(coordinationService, never()).applyInstruction("rp-1", "帮我先防守一下");
        verify(coordinationService, never()).beginVoiceInputHold(any(), any(Duration.class));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
