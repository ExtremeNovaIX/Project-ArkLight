package p1.component.gamer.adapter;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.junit.jupiter.api.Test;
import p1.component.agent.gamer.adapter.GameAdapter;
import p1.component.agent.gamer.adapter.core.GameAdapterContext;
import p1.component.agent.gamer.adapter.core.GameStateSnapshot;
import p1.component.agent.gamer.adapter.core.QueuedGameOperation;
import p1.config.mcp.MCPProperties;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameAdapterTest {

    @Test
    void shouldKeepStateFetchingAndParsingOutOfBaseAdapter() {
        assertFalse(hasDeclaredMethod("fetchStateWithTool"));
        assertFalse(hasDeclaredMethod("parseState"));
    }

    @Test
    void shouldSupportMatchingAdapterId() {
        TestAdapter adapter = new TestAdapter();
        MCPProperties.GameMCPConfig config = config("get_game_state");
        config.setAdapter("TEST");

        assertTrue(adapter.supports("any-game", config));
    }

    @Test
    void shouldRenderGenericOperationsWithoutStateTool() {
        TestAdapter adapter = new TestAdapter();
        ToolProviderResult.Builder tools = ToolProviderResult.builder();
        tools.add(tool("get_game_state", "state"), (request, memoryId) -> "{}");
        tools.add(tool("do_thing", "Do a thing"), (request, memoryId) -> "{}");
        tools.add(tool("inspect", "Inspect state"), (request, memoryId) -> "{}");

        String rendered = adapter.renderAvailableOperations(context(tools.build(), config("get_game_state")), null);

        assertFalse(rendered.contains("get_game_state"));
        assertTrue(rendered.contains("- do_thing: Do a thing"));
        assertTrue(rendered.contains("- inspect: Inspect state"));
    }

    @Test
    void shouldKeepDefaultQueueHookRequestUnchanged() {
        TestAdapter adapter = new TestAdapter();
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("do_thing")
                .arguments("{}")
                .build();
        QueuedGameOperation operation = new QueuedGameOperation(
                request,
                GameStateSnapshot.class.getSimpleName(),
                java.util.Map.of());

        ToolExecutionRequest repaired = adapter.repairBeforeExecute(
                context(ToolProviderResult.builder().build(), config("get_game_state")),
                operation,
                null);

        assertSame(request, repaired);
    }

    @Test
    void shouldSummarizeGenericOperationNames() {
        TestAdapter adapter = new TestAdapter();
        ToolProviderResult.Builder tools = ToolProviderResult.builder();
        tools.add(tool("get_game_state", "state"), (request, memoryId) -> "{}");
        tools.add(tool("do_thing", "Do a thing"), (request, memoryId) -> "{}");

        String summary = adapter.renderAvailableOperationSummary(context(tools.build(), config("get_game_state")), null);

        assertEquals("- do_thing", summary);
    }

    private static boolean hasDeclaredMethod(String methodName) {
        return Arrays.stream(GameAdapter.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals(methodName));
    }

    private static GameAdapterContext context(ToolProviderResult tools, MCPProperties.GameMCPConfig config) {
        return new GameAdapterContext("test-game", "session-1", tools, config);
    }

    private static MCPProperties.GameMCPConfig config(String stateToolName) {
        MCPProperties.GameMCPConfig config = new MCPProperties.GameMCPConfig();
        config.setAdapter("test");
        config.setStateToolName(stateToolName);
        return config;
    }

    private static ToolSpecification tool(String name, String description) {
        return ToolSpecification.builder()
                .name(name)
                .description(description)
                .parameters(JsonObjectSchema.builder().build())
                .build();
    }

    private static class TestAdapter extends GameAdapter {
        @Override
        public String id() {
            return "test";
        }

        @Override
        public GameStateSnapshot fetchState(GameAdapterContext context) {
            return new GameStateSnapshot("{}", null, "");
        }
    }
}