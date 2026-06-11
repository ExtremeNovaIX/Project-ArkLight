package p1.component.agent.stt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import p1.component.agent.interaction.GameCoordinationService;
import p1.component.agent.router.InstructionRouteDecision;
import p1.component.agent.router.InstructionRouteRequest;
import p1.component.agent.router.InstructionRouter;
import p1.component.agent.router.InstructionRouterTask;
import p1.component.agent.router.InstructionRouterTaskRegistry;
import p1.config.prop.AssistantProperties;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SttGameIntentGateTest {

    private AssistantProperties properties;
    private InstructionRouterTaskRegistry taskRegistry;
    private RecordingRouter router;
    private GameCoordinationService coordinationService;
    private MutableClock clock;
    private SttGameIntentGate gate;

    @BeforeEach
    void setUp() {
        properties = new AssistantProperties();
        taskRegistry = new InstructionRouterTaskRegistry();
        router = new RecordingRouter();
        coordinationService = mock(GameCoordinationService.class);
        when(coordinationService.hasActiveGame("rp-1")).thenReturn(true);
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        gate = new SttGameIntentGate(router, taskRegistry, coordinationService, properties, clock);
        gate.registerRouterTask();
    }

    @Test
    void shouldRegisterClosedSetRouterTask() {
        InstructionRouterTask task = taskRegistry.lookup(SttGameIntentGate.TASK_ID).orElseThrow();

        assertEquals(List.of("WAIT", "APPLY_INSTRUCTION", "READY", "CHAT"), task.allowedIntents());
        assertTrue(task.sceneInstruction().contains("混合语音"));
        assertTrue(task.sceneInstruction().contains("APPLY_INSTRUCTION"));
    }

    @Test
    void shouldTriggerWaitAndConsumeFinalText() {
        router.enqueue(new InstructionRouteDecision(true, "WAIT", 0.92, "等一下", "{}"));

        SttGameIntentGate.Result result = gate.onFinal("ws-1", "rp-1", "等一下");

        assertTrue(result.consumed());
        assertTrue(result.triggered());
        verify(coordinationService).waitForUser("rp-1", 0, "等一下");
    }

    @Test
    void shouldTriggerApplyInstructionWithRoutedInstruction() {
        router.enqueue(new InstructionRouteDecision(true, "APPLY_INSTRUCTION", 0.91, "先防更稳", "{}"));

        SttGameIntentGate.Result result = gate.onFinal("ws-1", "rp-1", "我觉得先防更好");

        assertTrue(result.consumed());
        verify(coordinationService).applyInstruction("rp-1", "先防更稳");
    }

    @Test
    void shouldTriggerReadyOnlyWhenWaiting() {
        when(coordinationService.isWaiting("rp-1")).thenReturn(false);
        router.enqueue(new InstructionRouteDecision(true, "READY", 0.95, "", "{}"));

        SttGameIntentGate.Result ignored = gate.onFinal("ws-1", "rp-1", "好了继续");

        assertFalse(ignored.consumed());
        verify(coordinationService, never()).markReady("rp-1");

        when(coordinationService.isWaiting("rp-1")).thenReturn(true);
        router.enqueue(new InstructionRouteDecision(true, "READY", 0.95, "", "{}"));

        SttGameIntentGate.Result consumed = gate.onFinal("ws-1", "rp-1", "好了继续");

        assertTrue(consumed.consumed());
        verify(coordinationService).markReady("rp-1");
    }

    @Test
    void shouldKeepLowConfidenceSpeechAsChat() {
        router.enqueue(new InstructionRouteDecision(true, "WAIT", 0.84, "等一下", "{}"));

        SttGameIntentGate.Result result = gate.onFinal("ws-1", "rp-1", "等一下");

        assertFalse(result.consumed());
        verify(coordinationService, never()).waitForUser("rp-1", 0, "等一下");
    }

    @Test
    void shouldIgnoreSpeechWhenNoActiveGame() {
        when(coordinationService.hasActiveGame("rp-1")).thenReturn(false);
        router.enqueue(new InstructionRouteDecision(true, "WAIT", 0.95, "等一下", "{}"));

        SttGameIntentGate.Result result = gate.onFinal("ws-1", "rp-1", "等一下");

        assertFalse(result.consumed());
        assertTrue(router.requests().isEmpty());
        verify(coordinationService, never()).waitForUser("rp-1", 0, "等一下");
    }

    @Test
    void shouldDedupeRepeatedPartialHypothesisAndConsumeMatchingFinal() {
        router.enqueue(new InstructionRouteDecision(true, "WAIT", 0.93, "先别动", "{}"));

        SttGameIntentGate.Result partial = gate.onPartial("ws-1", "rp-1", "先别动");
        clock.advance(Duration.ofMillis(1000));
        SttGameIntentGate.Result repeated = gate.onPartial("ws-1", "rp-1", "先别动");
        SttGameIntentGate.Result finished = gate.onFinal("ws-1", "rp-1", "先别动");

        assertTrue(partial.consumed());
        assertFalse(repeated.consumed());
        assertTrue(finished.consumed());
        assertEquals(1, router.requests().size());
        verify(coordinationService).waitForUser("rp-1", 0, "先别动");
    }

    @Test
    void shouldDeferShortPartialApplyInstructionUntilFinalText() {
        router.enqueue(new InstructionRouteDecision(true, "APPLY_INSTRUCTION", 0.95, "先", "{}"));
        router.enqueue(new InstructionRouteDecision(true, "WAIT", 0.93, "先别动", "{}"));

        SttGameIntentGate.Result partial = gate.onPartial("ws-1", "rp-1", "先");
        SttGameIntentGate.Result finished = gate.onFinal("ws-1", "rp-1", "先别动");

        assertFalse(partial.consumed());
        assertTrue(finished.consumed());
        verify(coordinationService, never()).applyInstruction("rp-1", "先");
        verify(coordinationService).waitForUser("rp-1", 0, "先别动");
    }

    @Test
    void shouldConsumeDuplicateIntentDuringCooldownWithoutRepeatingSideEffect() {
        router.enqueue(new InstructionRouteDecision(true, "WAIT", 0.93, "等一下", "{}"));
        router.enqueue(new InstructionRouteDecision(true, "WAIT", 0.94, "等一下", "{}"));

        SttGameIntentGate.Result first = gate.onFinal("ws-1", "rp-1", "等一下");
        clock.advance(Duration.ofMillis(2500));
        SttGameIntentGate.Result duplicate = gate.onFinal("ws-1", "rp-1", "等一下");

        assertTrue(first.triggered());
        assertTrue(duplicate.consumed());
        assertFalse(duplicate.triggered());
        assertEquals(2, router.requests().size());
        verify(coordinationService).waitForUser("rp-1", 0, "等一下");
    }

    @Test
    void dryRunShouldRouteIntentWithoutApplyingSideEffect() {
        router.enqueue(new InstructionRouteDecision(true, "WAIT", 0.93, "等一下", "{}"));

        SttGameIntentGate.Result result = gate.onFinalDryRun("ws-1", "rp-1", "等一下");

        assertTrue(result.consumed());
        assertFalse(result.triggered());
        assertTrue(result.routed());
        assertEquals("dry-run-would-trigger", result.reason());
        verify(coordinationService, never()).waitForUser("rp-1", 0, "等一下");
    }

    @Test
    void dryRunShouldStillRouteWhenNoActiveGame() {
        when(coordinationService.hasActiveGame("rp-1")).thenReturn(false);
        router.enqueue(new InstructionRouteDecision(true, "APPLY_INSTRUCTION", 0.93, "先防", "{}"));

        SttGameIntentGate.Result result = gate.onFinalDryRun("ws-1", "rp-1", "我觉得先防更好");

        assertTrue(result.consumed());
        assertEquals("APPLY_INSTRUCTION", result.intent());
        assertEquals(1, router.requests().size());
        verify(coordinationService, never()).applyInstruction("rp-1", "先防");
    }

    private static final class RecordingRouter implements InstructionRouter {
        private final ArrayDeque<InstructionRouteDecision> decisions = new ArrayDeque<>();
        private final List<InstructionRouteRequest> requests = new ArrayList<>();

        void enqueue(InstructionRouteDecision decision) {
            decisions.addLast(decision);
        }

        List<InstructionRouteRequest> requests() {
            return requests;
        }

        @Override
        public InstructionRouteDecision route(InstructionRouteRequest request) {
            requests.add(request);
            return decisions.isEmpty() ? InstructionRouteDecision.chat("default") : decisions.removeFirst();
        }
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
