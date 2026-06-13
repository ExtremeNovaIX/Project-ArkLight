package p1.component.rp.core;

import org.junit.jupiter.api.Test;
import p1.component.agent.gamer.loop.ActiveGameRegistry;
import p1.component.agent.rp.core.RpSystemPromptService;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpSystemPromptServiceTest {

    @Test
    void shouldNotAppendGameProtocolOutsideGameMode() {
        RpSystemPromptService service = new RpSystemPromptService(new ActiveGameRegistry());

        String prompt = service.build("rp-session", "你是 Nova。", "最近在闲聊。");

        assertTrue(prompt.contains("你是 Nova。"));
        assertTrue(prompt.contains("最近在闲聊。"));
        assertFalse(prompt.contains("游戏玩家AI"));
        assertFalse(prompt.contains("<game_output_lock>"));
    }

    @Test
    void shouldAppendGameProtocolWhenSessionHasActiveGame() {
        ActiveGameRegistry registry = new ActiveGameRegistry();
        registry.register("STS2MCP", "game-session", "rp-session");
        RpSystemPromptService service = new RpSystemPromptService(registry);

        String prompt = service.build("rp-session", "你是 Nova。", "正在爬塔。");

        assertTrue(prompt.contains("游戏玩家AI"));
        assertTrue(prompt.contains("\"type\": \"act\""));
        assertTrue(prompt.contains("<turn>"));
        assertTrue(prompt.contains("<game_output_lock>"));
    }
}
