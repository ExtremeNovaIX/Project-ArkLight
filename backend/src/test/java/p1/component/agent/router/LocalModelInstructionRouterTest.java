package p1.component.agent.router;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocalModelInstructionRouterTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldParseOpenAiCompatibleJsonDecision() throws Exception {
        startServer("""
                {
                  "choices": [
                    {
                      "message": {
                        "content": "{\\"intent\\":\\"WAIT\\",\\"confidence\\":0.91,\\"instruction\\":\\"先暂停\\",\\"reason\\":\\"用户要求等待\\"}"
                      }
                    }
                  ]
                }
                """);
        LocalModelInstructionRouter router = createRouter(modelConfig(server.getAddress().getPort()));

        InstructionRouteDecision decision = router.route(InstructionRouteRequest.byPrompt(
                "test-scene",
                "把用户输入分成 WAIT 或 CHAT",
                "waiting=false",
                "先停一下",
                List.of("WAIT", "CHAT")));

        assertTrue(decision.available());
        assertEquals("WAIT", decision.intent());
        assertEquals(0.91, decision.confidence(), 0.0001);
        assertEquals("先暂停", decision.instruction());
    }

    @Test
    void shouldReturnUnavailableWhenEndpointEmpty() {
        LocalModelInstructionRouter router = createRouter(new InstructionRouterModelConfig() {
            @Override
            public String endpointUrl() {
                return "";
            }
        });

        InstructionRouteDecision decision = router.route(InstructionRouteRequest.byPrompt(
                "test-scene",
                "",
                "",
                "先停一下",
                List.of("WAIT", "CHAT")));

        assertFalse(decision.available());
    }

    @Test
    void shouldReturnChatWhenRequestIsNull() {
        LocalModelInstructionRouter router = createRouter(new InstructionRouterModelConfig());

        InstructionRouteDecision decision = router.route(null);

        assertTrue(decision.available());
        assertEquals("CHAT", decision.intent());
    }

    @Test
    void shouldReturnChatWhenUserMessageIsBlank() {
        LocalModelInstructionRouter router = createRouter(new InstructionRouterModelConfig());

        InstructionRouteDecision decision = router.route(InstructionRouteRequest.byPrompt(
                "test-scene", "", "", "   ",
                List.of("WAIT", "CHAT")));

        assertTrue(decision.available());
        assertEquals("CHAT", decision.intent());
    }

    @Test
    void shouldExtractJsonFromWrappedText() throws Exception {
        startServer("""
                {
                  "choices": [
                    {
                      "message": {
                        "content": "这是一些前缀说明文本\\n{\\"intent\\":\\"READY\\",\\"confidence\\":0.88,\\"instruction\\":\\"\\",\\"reason\\":\\"用户确认继续\\"}\\n后缀文本"
                      }
                    }
                  ]
                }
                """);
        LocalModelInstructionRouter router = createRouter(modelConfig(server.getAddress().getPort()));

        InstructionRouteDecision decision = router.route(InstructionRouteRequest.byPrompt(
                "test-scene", "", "waiting=true", "好了继续吧",
                List.of("WAIT", "READY", "CHAT")));

        assertTrue(decision.available());
        assertEquals("READY", decision.intent());
        assertEquals(0.88, decision.confidence(), 0.0001);
    }

    @Test
    void shouldReturnUnavailableWhenResponseHasNoJson() throws Exception {
        startServer("""
                {
                  "choices": [
                    {
                      "message": {
                        "content": "这是一段纯文本响应，没有任何 JSON 结构"
                      }
                    }
                  ]
                }
                """);
        LocalModelInstructionRouter router = createRouter(modelConfig(server.getAddress().getPort()));

        InstructionRouteDecision decision = router.route(InstructionRouteRequest.byPrompt(
                "test-scene", "", "", "测试消息",
                List.of("WAIT", "CHAT")));

        assertFalse(decision.available());
    }

    @Test
    void shouldReturnChatWhenIntentNotInAllowedList() throws Exception {
        startServer("""
                {
                  "choices": [
                    {
                      "message": {
                        "content": "{\\"intent\\":\\"UNKNOWN_ACTION\\",\\"confidence\\":0.95,\\"instruction\\":\\"\\",\\"reason\\":\\"未知动作\\"}"
                      }
                    }
                  ]
                }
                """);
        LocalModelInstructionRouter router = createRouter(modelConfig(server.getAddress().getPort()));

        InstructionRouteDecision decision = router.route(InstructionRouteRequest.byPrompt(
                "test-scene", "", "", "做个未知操作",
                List.of("WAIT", "READY", "CHAT")));

        assertTrue(decision.available());
        assertEquals("CHAT", decision.intent());
    }

    @Test
    void shouldHandleTextResponseFormat() throws Exception {
        startServer("""
                {
                  "choices": [
                    {
                      "text": "{\\"intent\\":\\"WAIT\\",\\"confidence\\":0.76,\\"instruction\\":\\"暂停\\",\\"reason\\":\\"用户要求暂停\\"}"
                    }
                  ]
                }
                """);
        LocalModelInstructionRouter router = createRouter(modelConfig(server.getAddress().getPort()));

        InstructionRouteDecision decision = router.route(InstructionRouteRequest.byPrompt(
                "test-scene", "", "", "等等",
                List.of("WAIT", "CHAT")));

        assertTrue(decision.available());
        assertEquals("WAIT", decision.intent());
    }

    @Test
    void shouldHandleMissingFieldsWithDefaults() throws Exception {
        startServer("""
                {
                  "choices": [
                    {
                      "message": {
                        "content": "{\\"intent\\":\\"CHAT\\"}"
                      }
                    }
                  ]
                }
                """);
        LocalModelInstructionRouter router = createRouter(modelConfig(server.getAddress().getPort()));

        InstructionRouteDecision decision = router.route(InstructionRouteRequest.byPrompt(
                "test-scene", "", "", "随便聊聊",
                List.of("WAIT", "CHAT")));

        assertTrue(decision.available());
        assertEquals("CHAT", decision.intent());
        assertEquals(0.0, decision.confidence(), 0.0001);
        assertEquals("", decision.instruction());
    }

    @Test
    void shouldReturnUnavailableOnHttpError() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] bytes = "{\"error\":\"model not found\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        LocalModelInstructionRouter router = createRouter(modelConfig(server.getAddress().getPort()));

        InstructionRouteDecision decision = router.route(InstructionRouteRequest.byPrompt(
                "test-scene", "", "", "测试",
                List.of("WAIT", "CHAT")));

        assertFalse(decision.available());
    }

    @Test
    void shouldClampConfidenceToValidRange() throws Exception {
        startServer("""
                {
                  "choices": [
                    {
                      "message": {
                        "content": "{\\"intent\\":\\"WAIT\\",\\"confidence\\":2.5,\\"instruction\\":\\"\\",\\"reason\\":\\"超出范围的高置信度\\"}"
                      }
                    }
                  ]
                }
                """);
        LocalModelInstructionRouter router = createRouter(modelConfig(server.getAddress().getPort()));

        InstructionRouteDecision decision = router.route(InstructionRouteRequest.byPrompt(
                "test-scene", "", "", "等等",
                List.of("WAIT", "CHAT")));

        assertTrue(decision.available());
        assertEquals(1.0, decision.confidence(), 0.0001);
    }

    @Test
    void shouldHandleApplyInstructionIntent() throws Exception {
        startServer("""
                {
                  "choices": [
                    {
                      "message": {
                        "content": "{\\"intent\\":\\"APPLY_INSTRUCTION\\",\\"confidence\\":0.89,\\"instruction\\":\\"直接结束回合\\",\\"reason\\":\\"用户要求跳过当前回合\\"}"
                      }
                    }
                  ]
                }
                """);
        LocalModelInstructionRouter router = createRouter(modelConfig(server.getAddress().getPort()));

        InstructionRouteDecision decision = router.route(InstructionRouteRequest.byPrompt(
                "test-scene", "", "", "直接结束回合",
                List.of("WAIT", "READY", "APPLY_INSTRUCTION", "CHAT")));

        assertTrue(decision.available());
        assertEquals("APPLY_INSTRUCTION", decision.intent());
        assertEquals("直接结束回合", decision.instruction());
        assertEquals(0.89, decision.confidence(), 0.0001);
    }

    @Test
    void shouldReturnChatWhenAllowedIntentsIsNull() throws Exception {
        startServer("""
                {
                  "choices": [
                    {
                      "message": {
                        "content": "{\\"intent\\":\\"WAIT\\",\\"confidence\\":0.91,\\"instruction\\":\\"\\",\\"reason\\":\\"等待\\"}"
                      }
                    }
                  ]
                }
                """);
        LocalModelInstructionRouter router = createRouter(modelConfig(server.getAddress().getPort()));

        InstructionRouteDecision decision = router.route(InstructionRouteRequest.byPrompt(
                "test-scene", "", "", "等等",
                null));

        assertTrue(decision.available());
        assertEquals("CHAT", decision.intent());
    }

    @Test
    void shouldReturnChatWhenAllowedIntentsIsEmpty() throws Exception {
        startServer("""
                {
                  "choices": [
                    {
                      "message": {
                        "content": "{\\"intent\\":\\"WAIT\\",\\"confidence\\":0.91,\\"instruction\\":\\"\\",\\"reason\\":\\"等待\\"}"
                      }
                    }
                  ]
                }
                """);
        LocalModelInstructionRouter router = createRouter(modelConfig(server.getAddress().getPort()));

        InstructionRouteDecision decision = router.route(InstructionRouteRequest.byPrompt(
                "test-scene", "", "", "等等",
                List.of()));

        assertTrue(decision.available());
        assertEquals("CHAT", decision.intent());
    }

    @Test
    void shouldReturnUnavailableWhenEndpointUnreachable() {
        LocalModelInstructionRouter router = createRouter(new InstructionRouterModelConfig() {
            @Override
            public String endpointUrl() {
                return "http://127.0.0.1:19999/v1/chat/completions";
            }

            @Override
            public long timeoutMs() {
                return 200;
            }
        });

        InstructionRouteDecision decision = router.route(InstructionRouteRequest.byPrompt(
                "test-scene", "", "", "测试",
                List.of("WAIT", "CHAT")));

        assertFalse(decision.available());
    }

    @Test
    void shouldNormalizeIntentCase() throws Exception {
        startServer("""
                {
                  "choices": [
                    {
                      "message": {
                        "content": "{\\"intent\\":\\"wait\\",\\"confidence\\":0.85,\\"instruction\\":\\"\\",\\"reason\\":\\"小写意图\\"}"
                      }
                    }
                  ]
                }
                """);
        LocalModelInstructionRouter router = createRouter(modelConfig(server.getAddress().getPort()));

        InstructionRouteDecision decision = router.route(InstructionRouteRequest.byPrompt(
                "test-scene", "", "", "等等",
                List.of("WAIT", "CHAT")));

        assertTrue(decision.available());
        assertEquals("WAIT", decision.intent());
    }

    @Test
    void shouldReturnUnavailableOnTimeout() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        LocalModelInstructionRouter router = createRouter(new InstructionRouterModelConfig() {
            @Override
            public String endpointUrl() {
                return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
            }

            @Override
            public long timeoutMs() {
                return 100;
            }
        });

        InstructionRouteDecision decision = router.route(InstructionRouteRequest.byPrompt(
                "test-scene", "", "", "测试",
                List.of("WAIT", "CHAT")));

        assertFalse(decision.available());
    }

    private void startServer(String responseBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> writeJson(exchange, responseBody));
        server.start();
    }

    private void writeJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private LocalModelInstructionRouter createRouter(InstructionRouterModelConfig config) {
        return new LocalModelInstructionRouter(config, new InstructionRouterTaskRegistry());
    }

    private InstructionRouterModelConfig modelConfig(int port) {
        return new InstructionRouterModelConfig() {
            @Override
            public String endpointUrl() {
                return "http://127.0.0.1:" + port + "/v1/chat/completions";
            }

            @Override
            public String modelName() {
                return "test-router";
            }

            @Override
            public long timeoutMs() {
                return 1000;
            }
        };
    }
}
