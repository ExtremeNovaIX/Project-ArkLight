package p1.component.log;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.ansi.AnsiColor;
import org.springframework.boot.ansi.AnsiOutput;
import org.springframework.boot.ansi.AnsiStyle;
import org.springframework.stereotype.Component;
import p1.component.agent.reasoning.ReasoningContentRecorder;
import p1.config.prop.AssistantProperties;
import p1.infrastructure.mdc.ChatSessionMetrics;
import p1.utils.ChatMessageUtil;

import static p1.utils.SessionUtil.normalizeSessionId;

/**
 * 统一的 LLM 调用跟踪监听器工厂。
 * <p>
 * 控制台开关只决定是否输出可读诊断日志；token 统计和 reasoning 记录始终执行。
 */
@Component
@RequiredArgsConstructor
@Slf4j
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

            if (!assistantProperties.getLlmLogs().consoleEnabled(serviceName)) {
                return;
            }

            String input = formatLatestRequestMessage(context.chatRequest());
            String output = response == null || response.aiMessage() == null || response.aiMessage().text() == null
                    ? "[N/A]"
                    : response.aiMessage().text().trim();

            StringBuilder sb = new StringBuilder();
            sb.append("\n")
                    .append(AnsiOutput.toString(AnsiColor.BRIGHT_CYAN, AnsiStyle.BOLD,
                            "==================== [LLM调用跟踪开始] ====================",
                            AnsiStyle.NORMAL))
                    .append("\n")
                    .append(line("[服务]", serviceName))
                    .append(line("[调用]", valueOrDefault(MDC.get("serviceInfo"), "N/A")))
                    .append(line("[模型]", renderModelInfo()))
                    .append(line("[SessionId]", sessionId))
                    .append(line("[当前对话轮数]", resolveCurrentRound(sessionId)))
                    .append(AnsiOutput.toString(AnsiColor.CYAN, "[最新请求]\n", AnsiColor.DEFAULT, input)).append("\n");
            if (reasoningContent != null && !reasoningContent.isBlank()) {
                sb.append(AnsiOutput.toString(AnsiColor.BRIGHT_CYAN,
                        "[推理内容]\n", AnsiColor.DEFAULT, reasoningContent.trim())).append("\n");
            }
            sb.append(AnsiOutput.toString(AnsiColor.BRIGHT_WHITE,
                            "[响应]\n", AnsiColor.DEFAULT, output)).append("\n")
                    .append(tokenLine("[本次调用 Tokens]", currentTokens))
                    .append(cacheLine("[本次缓存命中]", currentTokens))
                    .append(tokenLine("[Session Tokens]", tokenTotals))
                    .append(cacheLine("[Session缓存命中]", tokenTotals))
                    .append(AnsiOutput.toString(AnsiColor.BRIGHT_CYAN, AnsiStyle.BOLD,
                            "==================== [LLM调用跟踪结束] ====================",
                            AnsiStyle.NORMAL))
                    .append("\n");

            log.info(sb.toString());
        }

        @Override
        public void onError(ChatModelErrorContext context) {
            if (!assistantProperties.getLlmLogs().consoleEnabled(serviceName)) {
                return;
            }
            log.error("[LLM调用失败] service={}, model={}, call={}, error={}",
                    serviceName, renderModelInfo(), valueOrDefault(MDC.get("serviceInfo"), "N/A"), context.error().toString());
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
            return AnsiOutput.toString(AnsiColor.WHITE, label, " ", AnsiColor.DEFAULT, String.valueOf(value), "\n");
        }

        private String tokenLine(String label, ChatSessionMetrics.TokenSnapshot snapshot) {
            return AnsiOutput.toString(AnsiColor.WHITE, label, " ",
                    AnsiColor.BRIGHT_YELLOW,
                    "[I:", snapshot.input(), " O:", snapshot.output(), " T:", snapshot.total(), "]\n",
                    AnsiColor.DEFAULT);
        }

        private String cacheLine(String label, ChatSessionMetrics.TokenSnapshot snapshot) {
            return AnsiOutput.toString(AnsiColor.WHITE, label, " ",
                    AnsiColor.BRIGHT_YELLOW,
                    "[Hit:", snapshot.cachedInput(), " I:", snapshot.input(), " Rate:", snapshot.cachedInputRatePercent(), "]\n",
                    AnsiColor.DEFAULT);
        }

        private int resolveCurrentRound(String sessionId) {
            String roundFromMdc = MDC.get("chatRound");
            if (roundFromMdc != null && !roundFromMdc.isBlank()) {
                try {
                    return Integer.parseInt(roundFromMdc);
                } catch (NumberFormatException ignored) {
                    // MDC 里没有合法轮次时使用累计计数。
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
