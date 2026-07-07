package p1.component.log;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import lombok.CustomLog;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import p1.component.agent.reasoning.ReasoningContentRecorder;
import p1.config.prop.AssistantProperties;
import p1.infrastructure.logging.LogDomain;
import p1.infrastructure.logging.LogOutcome;
import p1.infrastructure.mdc.ChatSessionMetrics;
import p1.utils.ChatMessageUtil;

import java.util.LinkedHashMap;
import java.util.Map;

import static p1.utils.SessionUtil.normalizeSessionId;

@Component
@RequiredArgsConstructor
@CustomLog
public class LlmServiceLoggingListenerFactory {


    private final AssistantProperties assistantProperties;
    private final ChatSessionMetrics chatSessionMetrics;
    private final ReasoningContentRecorder reasoningContentRecorder;

    public ChatModelListener create(String serviceName, AssistantProperties.ChatModelConfig modelConfig) {
        return new ServiceLoggingListener(serviceName, modelConfig);
    }

    private final class ServiceLoggingListener implements ChatModelListener {
        private final String serviceName;
        private final AssistantProperties.ChatModelConfig modelConfig;

        private ServiceLoggingListener(String serviceName, AssistantProperties.ChatModelConfig modelConfig) {
            this.serviceName = normalizeServiceName(serviceName);
            this.modelConfig = modelConfig;
        }

        @Override
        public void onResponse(ChatModelResponseContext context) {
            ChatResponse response = context.chatResponse();
            TokenUsage usage = response == null ? null : response.tokenUsage();
            String sessionId = normalizeSessionId(MDC.get("sessionId"));
            ChatSessionMetrics.TokenSnapshot currentTokens = ChatSessionMetrics.TokenSnapshot.from(usage);
            ChatSessionMetrics.TokenSnapshot tokenTotals = chatSessionMetrics.addAndGetTokenTotals(sessionId, usage);

            String reasoningContent = response == null || response.aiMessage() == null
                    ? ""
                    : response.aiMessage().thinking();
            reasoningContentRecorder.recordLatest(sessionId, reasoningContent);

            log.info(LogDomain.LLM, "call.completed", LogOutcome.SUCCEEDED,
                    summaryFields(sessionId, currentTokens));

            if (!assistantProperties.getLlmLogs().consoleEnabled(serviceName)) {
                return;
            }

            String input = formatLatestRequestMessage(context.chatRequest());
            String output = response == null || response.aiMessage() == null || response.aiMessage().text() == null
                    ? "[N/A]"
                    : response.aiMessage().text().trim();
            log.infoBlock(renderTraceBlock(sessionId, input, output, reasoningContent, currentTokens, tokenTotals));
        }

        @Override
        public void onError(ChatModelErrorContext context) {
            Throwable error = context.error();
            Map<String, Object> fields = baseFields(normalizeSessionId(MDC.get("sessionId")));
            fields.put("exception", error == null ? "N/A" : error.getClass().getSimpleName());
            fields.put("reason", error == null ? "N/A" : error.getMessage());
            log.error(LogDomain.LLM, "call.failed", LogOutcome.FAILED, fields);
        }

        private Map<String, Object> summaryFields(String sessionId, ChatSessionMetrics.TokenSnapshot tokens) {
            Map<String, Object> fields = baseFields(sessionId);
            fields.put("inputTokens", tokens.input());
            fields.put("outputTokens", tokens.output());
            fields.put("totalTokens", tokens.total());
            fields.put("cachedInputTokens", tokens.cachedInput());
            fields.put("cachedInputRate", tokens.cachedInputRatePercent());
            return fields;
        }

        private Map<String, Object> baseFields(String sessionId) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("service", serviceName);
            fields.put("call", valueOrDefault(MDC.get("serviceInfo"), "N/A"));
            fields.put("session", sessionId);
            fields.put("model", renderModelInfo());
            return fields;
        }

        private String renderTraceBlock(String sessionId,
                                        String input,
                                        String output,
                                        String reasoningContent,
                                        ChatSessionMetrics.TokenSnapshot currentTokens,
                                        ChatSessionMetrics.TokenSnapshot tokenTotals) {
            StringBuilder sb = new StringBuilder();
            sb.append('\n')
                    .append("==================== [LLM调用跟踪开始] ====================")
                    .append('\n')
                    .append(line("[服务]", serviceName))
                    .append(line("[调用]", valueOrDefault(MDC.get("serviceInfo"), "N/A")))
                    .append(line("[模型]", renderModelInfo()))
                    .append(line("[SessionId]", sessionId))
                    .append(line("[当前对话轮数]", resolveCurrentRound(sessionId)))
                    .append("[最新请求]\n")
                    .append(input)
                    .append('\n');
            if (reasoningContent != null && !reasoningContent.isBlank()) {
                sb.append("[推理内容]\n")
                        .append(reasoningContent.trim())
                        .append('\n');
            }
            sb.append("[响应]\n")
                    .append(output)
                    .append('\n')
                    .append(tokenLine("[本次调用 Tokens]", currentTokens))
                    .append(cacheLine("[本次缓存命中]", currentTokens))
                    .append(tokenLine("[Session Tokens]", tokenTotals))
                    .append(cacheLine("[Session缓存命中]", tokenTotals))
                    .append("==================== [LLM调用跟踪结束] ====================")
                    .append('\n');
            return sb.toString();
        }
        private String renderModelInfo() {
            if (modelConfig == null) {
                return "N/A";
            }
            return valueOrDefault(modelConfig.getModelName(), "N/A")
                    + " @ "
                    + valueOrDefault(modelConfig.getBaseUrl(), "N/A");
        }

        private String formatLatestRequestMessage(ChatRequest request) {
            if (request == null || request.messages() == null || request.messages().isEmpty()) {
                return "[N/A]";
            }
            int index = request.messages().size() - 1;
            ChatMessage message = request.messages().get(index);
            StringBuilder sb = new StringBuilder()
                    .append("[")
                    .append(index)
                    .append("] ")
                    .append(message.type());
            if (message instanceof UserMessage userMessage && userMessage.name() != null && !userMessage.name().isBlank()) {
                sb.append(" name=").append(userMessage.name());
            }
            return sb.append(":\n")
                    .append(ChatMessageUtil.extractText(message))
                    .toString()
                    .trim();
        }

        private String line(String label, Object value) {
            return label + " " + value + "\n";
        }

        private String tokenLine(String label, ChatSessionMetrics.TokenSnapshot snapshot) {
            return label + " [I:" + snapshot.input() + " O:" + snapshot.output() + " T:" + snapshot.total() + "]\n";
        }

        private String cacheLine(String label, ChatSessionMetrics.TokenSnapshot snapshot) {
            return label + " [Hit:" + snapshot.cachedInput() + " I:" + snapshot.input() + " Rate:" + snapshot.cachedInputRatePercent() + "]\n";
        }

        private int resolveCurrentRound(String sessionId) {
            String roundFromMdc = MDC.get("chatRound");
            if (roundFromMdc != null && !roundFromMdc.isBlank()) {
                try {
                    return Integer.parseInt(roundFromMdc);
                } catch (NumberFormatException ignored) {
                    // fall back to the accumulated session counter
                }
            }
            return chatSessionMetrics.getCurrentRound(sessionId);
        }

        private String normalizeServiceName(String value) {
            return value == null || value.isBlank() ? "unknown" : value.trim().toLowerCase();
        }

        private String valueOrDefault(String value, String defaultValue) {
            return value == null || value.isBlank() ? defaultValue : value.trim();
        }
    }
}
