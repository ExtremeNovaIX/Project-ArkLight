package p1.component.rp.proactive;

import dev.langchain4j.service.UserMessage;
import org.junit.jupiter.api.Test;
import p1.component.agent.rp.proactive.RpProactiveAgent;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpProactiveAgentTest {

    @Test
    void shouldNotHardcodeCurrentGameContextBlockInNonGamePrompt() throws Exception {
        Method speak = RpProactiveAgent.class.getMethod(
                "speak",
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class);

        String template = speak.getAnnotation(UserMessage.class).value()[0];

        assertTrue(template.contains("{{gameContext}}"));
        assertFalse(template.contains("<current_game_context>"));
    }
}
