package p1.component.log;

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
import p1.utils.ChatMessageUtil;
import p1.utils.LogUtil;

import static p1.utils.SessionUtil.normalizeSessionId;

@Component
@Slf4j
@RequiredArgsConstructor
public class AiServiceLoggingListener implements ChatModelListener {

    private final ChatSessionMetrics chatSessionMetrics;
    private final ReasoningContentRecorder reasoningContentRecorder;

    @Override
    public void onResponse(ChatModelResponseContext context) {
        ChatResponse response = context.chatResponse();
        TokenUsage usage = response.tokenUsage();
        ChatRequest request = context.chatRequest();
        String inputStr = ChatMessageUtil.formatMessageList(request.messages());

        String sessionId = normalizeSessionId(MDC.get("sessionId"));
        ChatSessionMetrics.TokenSnapshot currentTokens = ChatSessionMetrics.TokenSnapshot.from(usage);
        ChatSessionMetrics.TokenSnapshot tokenTotals = chatSessionMetrics.addAndGetTokenTotals(sessionId, usage);

        String reasoningContent = response.aiMessage() == null ? "" : response.aiMessage().thinking();
        reasoningContentRecorder.recordLatest(sessionId, reasoningContent);

        String aiOutput = response.aiMessage() == null || response.aiMessage().text() == null
                ? "[N/A]"
                : response.aiMessage().text();

        StringBuilder sb = new StringBuilder();
        sb.append("\n")
                .append(AnsiOutput.toString(AnsiColor.BRIGHT_GREEN, AnsiStyle.BOLD,
                        "==================== [后台LLM跟踪开始] ====================",
                        AnsiStyle.NORMAL))
                .append("\n");

        sb.append(AnsiOutput.toString(AnsiColor.WHITE, "[服务信息]: ", AnsiColor.DEFAULT, MDC.get("serviceInfo"))).append("\n");
        sb.append(AnsiOutput.toString(AnsiColor.WHITE, "[SessionId]: ", AnsiColor.DEFAULT, sessionId)).append("\n");
        sb.append(AnsiOutput.toString(AnsiColor.WHITE, "[请求]: ", AnsiColor.DEFAULT, LogUtil.trimTail(inputStr, 600))).append("\n");
        if (reasoningContent != null && !reasoningContent.isBlank()) {
            sb.append(AnsiOutput.toString(AnsiColor.BRIGHT_GREEN, "[推理内容]: ",
                    AnsiColor.DEFAULT, reasoningContent.trim())).append("\n");
        }
        sb.append(AnsiOutput.toString(AnsiColor.BRIGHT_GREEN, "[最终响应]: ", AnsiColor.DEFAULT, aiOutput.trim())).append("\n");
        sb.append(AnsiOutput.toString(AnsiColor.WHITE, "[本次调用 Tokens]: ",
                AnsiColor.BRIGHT_YELLOW, "[I:", currentTokens.input(), " O:", currentTokens.output(), " T:", currentTokens.total(), "]\n",
                AnsiColor.DEFAULT));
        sb.append(AnsiOutput.toString(AnsiColor.WHITE, "[本次缓存命中]: ",
                AnsiColor.BRIGHT_YELLOW,
                "[Hit:", currentTokens.cachedInput(), " I:", currentTokens.input(), " Rate:", currentTokens.cachedInputRatePercent(), "]\n",
                AnsiColor.DEFAULT));
        sb.append(AnsiOutput.toString(AnsiColor.WHITE, "[Session Tokens]: ",
                AnsiColor.BRIGHT_YELLOW, "[I:", tokenTotals.input(), " O:", tokenTotals.output(), " T:", tokenTotals.total(), "]\n",
                AnsiColor.DEFAULT));
        sb.append(AnsiOutput.toString(AnsiColor.WHITE, "[Session缓存命中]: ",
                AnsiColor.BRIGHT_YELLOW,
                "[Hit:", tokenTotals.cachedInput(), " I:", tokenTotals.input(), " Rate:", tokenTotals.cachedInputRatePercent(), "]\n",
                AnsiColor.DEFAULT));
        sb.append(AnsiOutput.toString(AnsiColor.BRIGHT_GREEN, AnsiStyle.BOLD,
                        "==================== [后台LLM跟踪结束] ====================",
                        AnsiStyle.NORMAL))
                .append("\n");

        log.info(sb.toString());
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        log.error("调用失败，Error: {}", context.error().toString());
    }
}
