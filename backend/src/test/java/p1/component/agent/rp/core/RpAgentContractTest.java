package p1.component.agent.rp.core;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.UserName;
import dev.langchain4j.service.V;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RpAgentContractTest {

    @Test
    void gameLoopEntryShouldExposeNamedUserMessage() throws Exception {
        Method method = RpAgent.class.getMethod(
                "chatWithName",
                String.class,
                String.class,
                String.class,
                String.class);
        Parameter[] parameters = method.getParameters();

        assertTrue(parameters[0].isAnnotationPresent(MemoryId.class));
        assertTrue(parameters[1].isAnnotationPresent(UserName.class));
        assertTrue(parameters[2].isAnnotationPresent(UserMessage.class));
        assertTrue(parameters[3].isAnnotationPresent(V.class));
    }
}
