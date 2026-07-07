package p1.component.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import p1.component.agent.reasoning.ReasoningContentRecorder;
import p1.config.prop.AssistantProperties;
import p1.infrastructure.mdc.ChatSessionMetrics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmServiceLoggingListenerFactoryTest {

    @Test
    void shouldWriteFullAgentTraceToAppLogger() {
        AssistantProperties properties = new AssistantProperties();
        properties.getLlmLogs().getConsole().put("rp", true);
        ChatSessionMetrics metrics = new ChatSessionMetrics();
        ReasoningContentRecorder reasoningRecorder = new ReasoningContentRecorder();
        AssistantProperties.ChatModelConfig model = new AssistantProperties.ChatModelConfig();
        model.setModelName("deepseek-reasoner");
        model.setBaseUrl("https://api.example.test");

        Logger appLogger = (Logger) LoggerFactory.getLogger(LlmServiceLoggingListenerFactory.class);
        ListAppender<ILoggingEvent> appAppender = attach(appLogger);
        try {
            MDC.put("sessionId", "qt-session");
            MDC.put("chatRound", "12");
            MDC.put("serviceInfo", "RpAiService:chat");

            ChatModelResponseContext context = mock(ChatModelResponseContext.class);
            ChatResponse response = ChatResponse.builder()
                    .aiMessage(AiMessage.builder()
                            .text("final response")
                            .thinking("hidden reasoning")
                            .build())
                    .tokenUsage(new TokenUsage(1243, 312, 1555))
                    .build();
            ChatRequest request = ChatRequest.builder()
                    .messages(UserMessage.from("latest user request"))
                    .build();
            when(context.chatResponse()).thenReturn(response);
            when(context.chatRequest()).thenReturn(request);

            new LlmServiceLoggingListenerFactory(properties, metrics, reasoningRecorder)
                    .create("rp", model)
                    .onResponse(context);

            String appMessage = appAppender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains("event=call.completed"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(appMessage.contains("domain=LLM"));
            assertTrue(appMessage.contains("service=rp"));
            assertTrue(appMessage.contains("call=RpAiService:chat"));
            assertTrue(appMessage.contains("session=qt-session"));
            assertTrue(appMessage.contains("inputTokens=1243"));
            assertTrue(appMessage.contains("outputTokens=312"));
            assertFalse(appMessage.contains("latest user request"));
            assertFalse(appMessage.contains("hidden reasoning"));
            assertFalse(appMessage.contains("final response"));

            String traceMessage = appAppender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains("[LLM\u8c03\u7528\u8ddf\u8e2a\u5f00\u59cb]"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(traceMessage.contains("[\u670d\u52a1] rp"));
            assertTrue(traceMessage.contains("[\u8c03\u7528] RpAiService:chat"));
            assertTrue(traceMessage.contains("[\u6a21\u578b] deepseek-reasoner @ https://api.example.test"));
            assertTrue(traceMessage.contains("[SessionId] qt-session"));
            assertTrue(traceMessage.contains("[\u5f53\u524d\u5bf9\u8bdd\u8f6e\u6570] 12"));
            assertTrue(traceMessage.contains("[\u6700\u65b0\u8bf7\u6c42]"));
            assertTrue(traceMessage.contains("latest user request"));
            assertTrue(traceMessage.contains("[\u63a8\u7406\u5185\u5bb9]"));
            assertTrue(traceMessage.contains("hidden reasoning"));
            assertTrue(traceMessage.contains("[\u54cd\u5e94]"));
            assertTrue(traceMessage.contains("final response"));
            assertTrue(traceMessage.contains("[\u672c\u6b21\u8c03\u7528 Tokens] [I:1243 O:312 T:1555]"));
            assertTrue(traceMessage.contains("[Session Tokens] [I:1243 O:312 T:1555]"));
            assertEquals("hidden reasoning", reasoningRecorder.consumeLatest("qt-session"));
            assertTrue(appAppender.list.stream().anyMatch(event -> event.getLevel() == Level.INFO));
        } finally {
            MDC.clear();
            detach(appLogger, appAppender);
        }
    }

    private ListAppender<ILoggingEvent> attach(Logger logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detach(Logger logger, ListAppender<ILoggingEvent> appender) {
        logger.detachAppender(appender);
        appender.stop();
    }
}