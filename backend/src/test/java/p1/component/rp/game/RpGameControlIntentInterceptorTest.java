package p1.component.rp.game;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import org.junit.jupiter.api.Test;
import p1.component.agent.gamer.interrupt.GameInterruptService;
import p1.component.agent.gamer.loop.ActiveGameRegistry;
import p1.component.agent.gamer.loop.GameLoopObservationBackoffService;
import p1.component.agent.interaction.GameCoordinationService;
import p1.component.agent.interaction.InteractionCoordinator;
import p1.component.agent.router.InstructionRouteDecision;
import p1.component.agent.router.InstructionRouter;
import p1.component.agent.router.InstructionRouterModelConfig;
import p1.component.agent.router.InstructionRouterTaskRegistry;
import p1.component.agent.rp.game.control.RpGameControlIntentInterceptor;
import p1.component.agent.rp.proactive.RpLiveMessageHub;
import p1.component.agent.rp.proactive.RpProactiveSessionRegistry;
import p1.config.prop.AssistantProperties;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class RpGameControlIntentInterceptorTest {

    @Test
    void shouldRegisterWaitForExplicitWaitMessage() {
        Fixture fixture = new Fixture();
        fixture.registry.register("STS2MCP", "game-session", "rp-session");

        Optional<RpGameControlIntentInterceptor.HandledGameControlIntent> result =
                fixture.interceptor.intercept("rp-session", "等等，我想看一看局面");

        assertTrue(result.isPresent());
        assertEquals("WAIT", result.orElseThrow().action());
        assertFalse(fixture.coordinator.canGameActForRpSession("rp-session").allowed());
        assertTrue(fixture.coordinationService.isWaiting("rp-session"));
    }

    @Test
    void shouldRegisterWaitForStopMessage() {
        Fixture fixture = new Fixture();
        fixture.registry.register("STS2MCP", "game-session", "rp-session");

        Optional<RpGameControlIntentInterceptor.HandledGameControlIntent> result =
                fixture.interceptor.intercept("rp-session", "先停一下");

        assertTrue(result.isPresent());
        assertEquals("WAIT", result.orElseThrow().action());
        assertFalse(fixture.coordinator.canGameActForRpSession("rp-session").allowed());
    }

    @Test
    void shouldReleaseWaitWhenUserSaysReady() {
        Fixture fixture = new Fixture();
        fixture.registry.register("STS2MCP", "game-session", "rp-session");
        fixture.interceptor.intercept("rp-session", "先停一下");

        Optional<RpGameControlIntentInterceptor.HandledGameControlIntent> result =
                fixture.interceptor.intercept("rp-session", "好了，继续吧");

        assertTrue(result.isPresent());
        assertEquals("READY", result.orElseThrow().action());
        assertTrue(fixture.coordinator.canGameActForRpSession("rp-session").allowed());
        assertFalse(fixture.coordinationService.isWaiting("rp-session"));
    }

    @Test
    void shouldIgnoreWaitTextOutsideGameMode() {
        Fixture fixture = new Fixture();

        Optional<RpGameControlIntentInterceptor.HandledGameControlIntent> result =
                fixture.interceptor.intercept("rp-session", "等等");

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldRespectSimpleWaitNegation() {
        Fixture fixture = new Fixture();
        fixture.registry.register("STS2MCP", "game-session", "rp-session");

        Optional<RpGameControlIntentInterceptor.HandledGameControlIntent> result =
                fixture.interceptor.intercept("rp-session", "不用停，继续走");

        assertTrue(result.isEmpty());
        assertTrue(fixture.coordinator.canGameActForRpSession("rp-session").allowed());
    }

    @Test
    void shouldUseInstructionRouterBeforeRuleFallback() {
        Fixture fixture = new Fixture(request ->
                new InstructionRouteDecision(true, "WAIT", 0.91, "", "用户要观察局面", "{}"));
        fixture.registry.register("STS2MCP", "game-session", "rp-session");
        fixture.properties.getRp().setInstructionRouterEnabled(true);

        Optional<RpGameControlIntentInterceptor.HandledGameControlIntent> result =
                fixture.interceptor.intercept("rp-session", "我需要观察一下这一手");

        assertTrue(result.isPresent());
        assertEquals("WAIT", result.orElseThrow().action());
        assertFalse(fixture.coordinator.canGameActForRpSession("rp-session").allowed());
    }

    @Test
    void shouldLetReadyIntentReleaseExistingWait() {
        Fixture fixture = new Fixture();
        fixture.registry.register("STS2MCP", "game-session", "rp-session");
        fixture.interceptor.intercept("rp-session", "先停一下");

        fixture = new Fixture(request ->
                new InstructionRouteDecision(true, "READY", 0.92, "", "用户取消等待", "{}"),
                fixture.registry,
                fixture.coordinator,
                fixture.coordinationService,
                fixture.properties);
        fixture.properties.getRp().setInstructionRouterEnabled(true);

        Optional<RpGameControlIntentInterceptor.HandledGameControlIntent> result =
                fixture.interceptor.intercept("rp-session", "别等了，继续操作");

        assertTrue(result.isPresent());
        assertEquals("READY", result.orElseThrow().action());
        assertTrue(fixture.coordinator.canGameActForRpSession("rp-session").allowed());
    }

    @Test
    void shouldNotFallbackWhenRouterConfidentlyReturnsChat() {
        Fixture fixture = new Fixture(request ->
                new InstructionRouteDecision(true, "CHAT", 0.95, "", "普通聊天", "{}"));
        fixture.registry.register("STS2MCP", "game-session", "rp-session");
        fixture.properties.getRp().setInstructionRouterEnabled(true);

        Optional<RpGameControlIntentInterceptor.HandledGameControlIntent> result =
                fixture.interceptor.intercept("rp-session", "等等，这里刚才为什么这么打");

        assertTrue(result.isEmpty());
        assertTrue(fixture.coordinator.canGameActForRpSession("rp-session").allowed());
    }

    @Test
    void shouldRouteEveryUserMessageWhenRpRouterEnabled() {
        AtomicInteger routeCount = new AtomicInteger();
        Fixture fixture = new Fixture(request -> {
            routeCount.incrementAndGet();
            return new InstructionRouteDecision(true, "CHAT", 0.98, "", "普通 RP 对话", "{}");
        });
        fixture.properties.getRp().setInstructionRouterEnabled(true);

        Optional<RpGameControlIntentInterceptor.HandledGameControlIntent> result =
                fixture.interceptor.intercept("rp-session", "今天先闲聊一下");

        assertTrue(result.isEmpty());
        assertEquals(1, routeCount.get());
    }

    private static final class Fixture {
        private final AssistantProperties properties;
        private final ActiveGameRegistry registry;
        private final InteractionCoordinator coordinator;
        private final GameCoordinationService coordinationService;
        private final RpGameControlIntentInterceptor interceptor;

        private Fixture() {
            this(request -> InstructionRouteDecision.unavailable("test router disabled"));
        }

        private Fixture(InstructionRouter instructionRouter) {
            this(
                    instructionRouter,
                    new ActiveGameRegistry(),
                    null,
                    null,
                    new AssistantProperties());
        }

        private Fixture(InstructionRouter instructionRouter,
                        ActiveGameRegistry registry,
                        InteractionCoordinator coordinator,
                        GameCoordinationService coordinationService,
                        AssistantProperties properties) {
            this.properties = properties;
            this.registry = registry;
            this.coordinator = coordinator == null ? new InteractionCoordinator(registry, properties) : coordinator;
            this.coordinationService = coordinationService == null
                    ? new GameCoordinationService(
                    registry,
                    new GameInterruptService(),
                    this.coordinator,
                    new GameLoopObservationBackoffService(),
                    mock(RpLiveMessageHub.class),
                    mock(RpProactiveSessionRegistry.class),
                    mock(ChatMemoryProvider.class),
                    properties,
                    Runnable::run)
                    : coordinationService;
            this.interceptor = new RpGameControlIntentInterceptor(
                    this.coordinationService,
                    instructionRouter,
                    new InstructionRouterModelConfig(),
                    new InstructionRouterTaskRegistry(),
                    properties);
        }
    }
}
