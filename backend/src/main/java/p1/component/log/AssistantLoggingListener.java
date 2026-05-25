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
import p1.infrastructure.mdc.ChatSessionMetrics;
import p1.utils.LogUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static p1.utils.SessionUtil.normalizeSessionId;

@Component
@Slf4j
@RequiredArgsConstructor
public class AssistantLoggingListener implements ChatModelListener {

    private final ChatSessionMetrics chatSessionMetrics;
    private final ReasoningContentRecorder reasoningContentRecorder;
    private final Map<String, String> pendingInputs = new ConcurrentHashMap<>();

    @Override
    public void onResponse(ChatModelResponseContext context) {
        ChatResponse response = context.chatResponse();
        ChatRequest request = context.chatRequest();
        TokenUsage usage = response.tokenUsage();

        String sessionId = normalizeSessionId(MDC.get("sessionId"));
        int currentRound = resolveCurrentRound(sessionId);
        String cacheKey = buildCacheKey(sessionId, currentRound);
        String reasoningContent = response.aiMessage() == null ? "" : response.aiMessage().thinking();
        reasoningContentRecorder.recordLatest(sessionId, reasoningContent);

        String currentInput = extractUserInput(request);
        if (!currentInput.isBlank()) {
            pendingInputs.put(cacheKey, currentInput);
        }

        String aiOutput = response.aiMessage().text();
        if (aiOutput == null || aiOutput.isBlank()) {
            return;
        }

        String input = currentInput.isBlank() ? pendingInputs.remove(cacheKey) : pendingInputs.remove(cacheKey);
        if (input == null || input.isBlank()) {
            input = "N/A";
        }

        ChatSessionMetrics.TokenSnapshot currentTokens = ChatSessionMetrics.TokenSnapshot.from(usage);
        ChatSessionMetrics.TokenSnapshot tokenTotals = chatSessionMetrics.addAndGetTokenTotals(sessionId, usage);

        StringBuilder sb = new StringBuilder();
        sb.append("\n")
                .append(AnsiOutput.toString(AnsiColor.BRIGHT_CYAN, AnsiStyle.BOLD,
                        ">>> [前端AI交互流程]=================================================================", AnsiStyle.NORMAL))
                .append("\n")
                .append(AnsiOutput.toString(AnsiColor.WHITE, "[SessionId]: ", AnsiColor.DEFAULT, sessionId)).append("\n")
                .append(AnsiOutput.toString(AnsiColor.WHITE, "[当前对话轮数]: ", AnsiColor.DEFAULT, currentRound)).append("\n")
                .append(AnsiOutput.toString(AnsiColor.CYAN, "[请求]: " + input)).append("\n")
                .append(AnsiOutput.toString(AnsiColor.BRIGHT_WHITE, "[输出]: " + aiOutput.trim())).append("\n");
        if (reasoningContent != null && !reasoningContent.isBlank()) {
            sb.append(AnsiOutput.toString(AnsiColor.BRIGHT_CYAN,
                    "[推理内容]: " + LogUtil.trimTail(reasoningContent.trim(), 1200))).append("\n");
        }
        sb
                .append(AnsiOutput.toString(AnsiColor.WHITE, "[本次调用 Tokens]: ",
                        AnsiColor.BRIGHT_YELLOW, "[I:", currentTokens.input(), " O:", currentTokens.output(), " T:", currentTokens.total(), "]",
                        AnsiColor.DEFAULT)).append("\n")
                .append(AnsiOutput.toString(AnsiColor.WHITE, "[本次缓存命中]: ",
                        AnsiColor.BRIGHT_YELLOW,
                        "[Hit:", currentTokens.cachedInput(), " I:", currentTokens.input(), " Rate:", currentTokens.cachedInputRatePercent(), "]",
                        AnsiColor.DEFAULT)).append("\n")
                .append(AnsiOutput.toString(AnsiColor.WHITE, "[Session Tokens]: ",
                        AnsiColor.BRIGHT_YELLOW, "[I:", tokenTotals.input(), " O:", tokenTotals.output(), " T:", tokenTotals.total(), "]",
                        AnsiColor.DEFAULT)).append("\n")
                .append(AnsiOutput.toString(AnsiColor.WHITE, "[Session缓存命中]: ",
                        AnsiColor.BRIGHT_YELLOW,
                        "[Hit:", tokenTotals.cachedInput(), " I:", tokenTotals.input(), " Rate:", tokenTotals.cachedInputRatePercent(), "]",
                        AnsiColor.DEFAULT)).append("\n")
                .append(AnsiOutput.toString(AnsiColor.BRIGHT_CYAN,
                        "=================================================================================================="));
        log.info(sb.toString());
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        log.error("调用失败 | Error: {}", context.error().toString());
    }

    private String extractUserInput(ChatRequest request) {
        if (request == null || request.messages().isEmpty()) {
            return "";
        }

        for (int i = request.messages().size() - 2; i >= 0; i--) {
            ChatMessage message = request.messages().get(i);
            if (message instanceof UserMessage userMessage) {
                String[] parts = userMessage.singleText().split("user:");
                return parts[parts.length - 1].trim();
            }
        }
        return "";
    }

    private String buildCacheKey(String sessionId, int currentRound) {
        return sessionId + "#" + currentRound;
    }

    private int resolveCurrentRound(String sessionId) {
        String roundFromMdc = MDC.get("chatRound");
        if (roundFromMdc != null && !roundFromMdc.isBlank()) {
            try {
                return Integer.parseInt(roundFromMdc);
            } catch (NumberFormatException ignored) {
            }
        }
        return chatSessionMetrics.getCurrentRound(sessionId);
    }
}
