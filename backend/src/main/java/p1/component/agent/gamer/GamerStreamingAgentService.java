package p1.component.agent.gamer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.service.TokenStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import p1.component.agent.gamer.bridge.GameBridgeActionStatus;
import p1.component.agent.gamer.bridge.GameBridgeService;
import p1.component.agent.reasoning.ReasoningContentRecorder;
import p1.component.agent.streaming.StreamingJsonInstruction;
import p1.component.agent.streaming.StreamingJsonInstructionParser;
import p1.config.mcp.GamerProperties;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * gamer 的流式微指令执行服务。
 * <p>
 * 该服务通过 GamerStreamingAgent 获取 TokenStream，持续解析模型输出中的 JSON 操作对象；
 * 每解析到一个有效 action 就立刻交给 GameBridgeService 执行，底层仍复用队列处理器。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GamerStreamingAgentService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final GamerStreamingAgent streamingAgent;
    private final GameBridgeService bridgeService;
    private final GamerMCPClientFactory mcpClientFactory;
    private final GamerProperties gamerProperties;
    private final ReasoningContentRecorder reasoningContentRecorder;

    /**
     * 以流式 JSON 指令协议处理一次 gamer 决策。
     *
     * @param gameName    游戏名
     * @param sessionId   用户侧会话 id
     * @param userMessage 外层传入的用户指令
     * @return 已执行操作的桥接层结果摘要
     */
    public String play(String gameName, String sessionId, String userMessage) {
        String memoryId = GameSessionKey.of(gameName, sessionId);
        String displayName = mcpClientFactory.getGameDisplayName(gameName);
        String gameGuidelines = mcpClientFactory.getGameGuidelines(gameName);
        String context = bridgeService.buildAgentContext(gameName, sessionId, userMessage);
        StreamingTurnHandler handler = new StreamingTurnHandler(gameName, sessionId, memoryId);
        TokenStream stream = streamingAgent.play(memoryId, context, displayName, gameGuidelines);
        stream.onPartialResponseWithContext(handler::onPartialResponse)
                .onPartialThinkingWithContext(handler::onPartialThinking)
                .onCompleteResponse(handler::onCompleteResponse)
                .onError(handler::onError)
                .start();
        return handler.awaitResult();
    }

    /**
     * 查询本轮游戏智能体通过桥接层提交的结构化动作状态。
     *
     * @param gameName  游戏名
     * @param sessionId 用户侧会话 id
     * @return 最近一次 ACTION 队列状态；本轮没有提交时返回 UNKNOWN
     */
    public GameBridgeActionStatus lastActionStatus(String gameName, String sessionId) {
        return bridgeService.lastActionStatus(gameName, sessionId);
    }

    /**
     * 单次流式模型调用的回调处理器。
     */
    private class StreamingTurnHandler implements StreamingChatResponseHandler {
        private final String gameName;
        private final String sessionId;
        private final String memoryId;
        private final GamerProperties.Streaming config = gamerProperties.getStreaming();
        private final StreamingJsonInstructionParser thinkingParser =
                new StreamingJsonInstructionParser(objectMapper, config.getMaxJsonCandidateChars());
        private final StreamingJsonInstructionParser responseParser =
                new StreamingJsonInstructionParser(objectMapper, config.getMaxJsonCandidateChars());
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicReference<StreamingHandle> streamingHandle = new AtomicReference<>();
        private final AtomicInteger actionCount = new AtomicInteger(0);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final StringBuilder thinkingBuffer = new StringBuilder();
        private final StringBuilder responseBuffer = new StringBuilder();
        private final StringBuilder actionBuffer = new StringBuilder();
        private final StringBuilder executionResults = new StringBuilder();
        private final long startedAt = System.currentTimeMillis();
        private volatile Throwable error;

        /**
         * 创建单次流式回调处理器。
         */
        private StreamingTurnHandler(String gameName, String sessionId, String memoryId) {
            this.gameName = gameName;
            this.sessionId = sessionId;
            this.memoryId = memoryId;
        }

        @Override
        public void onPartialResponse(String partialResponse) {
            handleChunk(partialResponse, false);
        }

        @Override
        public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
            rememberHandle(context.streamingHandle());
            handleChunk(partialResponse.text(), false);
        }

        @Override
        public void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext context) {
            rememberHandle(context.streamingHandle());
            handleChunk(partialThinking.text(), true);
        }

        @Override
        public void onCompleteResponse(ChatResponse completeResponse) {
            finish();
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
            finish();
        }

        /**
         * 等待流式调用完成，并返回本次已经执行的桥接结果。
         */
        private String awaitResult() {
            try {
                boolean done = finished.await(Math.max(1000, config.getMaxStreamWaitMs()), TimeUnit.MILLISECONDS);
                if (!done) {
                    cancelStream("流式调用超时");
                    throw new IllegalStateException("gamer 流式调用超时: memoryId=" + memoryId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelStream("等待流式调用被中断");
                throw new IllegalStateException("等待 gamer 流式调用被中断: memoryId=" + memoryId, e);
            }
            if (error != null) {
                throw new IllegalStateException("gamer 流式调用失败: " + error.getMessage(), error);
            }
            if (!executionResults.isEmpty()) {
                return executionResults.toString().trim();
            }
            return responseBuffer.isEmpty() ? "流式调用完成，但未捕获到可执行 JSON 操作。" : responseBuffer.toString().trim();
        }

        /**
         * 处理模型输出片段，并把完整 action JSON 派发给桥接层。
         */
        private synchronized void handleChunk(String text, boolean thinking) {
            if (closed.get() || text == null || text.isBlank()) {
                return;
            }
            if (thinking) {
                thinkingBuffer.append(text);
            } else {
                responseBuffer.append(text);
            }
            if (isFirstActionTimeout()) {
                log.warn("[gamer流式] 首个 ACTION 超时前模型输出: game={}, memoryId={}, thinkingTail={}, responseTail={}",
                        gameName,
                        memoryId,
                        tail(thinkingBuffer.toString(), 1000),
                        tail(responseBuffer.toString(), 1000));
                cancelStream("首个 ACTION 超时");
                finish();
                return;
            }

            List<StreamingJsonInstruction> instructions = parseInstructions(text, thinking);
            for (StreamingJsonInstruction instruction : instructions) {
                if (!isActionInstruction(instruction.json())) {
                    continue;
                }
                dispatchAction(instruction);
                if (closed.get()) {
                    return;
                }
            }
        }

        /**
         * 按配置从 thinking 或 response 通道解析 ACTION JSON。
         */
        private List<StreamingJsonInstruction> parseInstructions(String text, boolean thinking) {
            if (thinking) {
                return config.isParseThinkingChunks() ? thinkingParser.accept(text) : List.of();
            }
            return config.isParseResponseChunks() ? responseParser.accept(text) : List.of();
        }

        /**
         * 判断 JSON 是否是 gamer action 指令。
         */
        private boolean isActionInstruction(JsonNode json) {
            if (json == null || !json.isObject() || !json.has("operations")) {
                return false;
            }
            String type = json.path("type").asText("");
            return type.isBlank()
                    || "action".equalsIgnoreCase(type)
                    || "enqueue_operations".equalsIgnoreCase(type);
        }

        /**
         * 执行单个 action JSON。
         */
        private void dispatchAction(StreamingJsonInstruction instruction) {
            int currentAction = actionCount.incrementAndGet();
            recordReasoningSnapshot();
            appendActionLog(currentAction, instruction.rawJson());
            String result = bridgeService.executeOperationQueue(gameName, sessionId, renderStreamingArguments(instruction));
            executionResults.append(result).append("\n");

            GameBridgeActionStatus status = bridgeService.lastActionStatus(gameName, sessionId);
            if (status == GameBridgeActionStatus.INTERRUPTED || status == GameBridgeActionStatus.GAME_OVER) {
                appendActionLog(currentAction, "桥接层返回硬边界状态：" + status);
                cancelStream("桥接层返回硬边界状态: " + status);
                finish();
                return;
            }
            if (currentAction >= Math.max(1, config.getMaxActionsPerStream())) {
                appendActionLog(currentAction, "达到单次 stream 最大 ACTION 数，取消当前 stream。");
                cancelStream("达到单次 stream 最大 ACTION 数");
                finish();
            }
        }

        /**
         * 收集本次流式调用解析出的 ACTION，最终随汇总日志一次性打印。
         */
        private void appendActionLog(int actionIndex, String text) {
            actionBuffer.append("[ACTION ")
                    .append(actionIndex)
                    .append("] ")
                    .append(text)
                    .append("\n");
        }

        /**
         * 给流式 action 增加“后续仍可能有操作”的内部标记。
         */
        private String renderStreamingArguments(StreamingJsonInstruction instruction) {
            ObjectNode copy = instruction.json().deepCopy();
            copy.put("_expect_more_operations", true);
            try {
                return objectMapper.writeValueAsString(copy);
            } catch (Exception e) {
                return instruction.rawJson();
            }
        }

        /**
         * 记录供应商返回的 reasoning_content，供队列处理器写入复盘日志。
         */
        private void recordReasoningSnapshot() {
            if (thinkingBuffer.isEmpty()) {
                return;
            }
            reasoningContentRecorder.recordLatest(memoryId, tail(thinkingBuffer.toString(), config.getReasoningTraceMaxChars()));
        }

        /**
         * 判断首个 ACTION 是否已经等待过久。
         */
        private boolean isFirstActionTimeout() {
            return actionCount.get() == 0
                    && config.getFirstActionTimeoutMs() > 0
                    && System.currentTimeMillis() - startedAt > config.getFirstActionTimeoutMs();
        }

        /**
         * 保存 LangChain4j 提供的流控制句柄。
         */
        private void rememberHandle(StreamingHandle handle) {
            if (handle != null) {
                streamingHandle.compareAndSet(null, handle);
            }
        }

        /**
         * 取消当前模型流。
         */
        private void cancelStream(String reason) {
            StreamingHandle handle = streamingHandle.get();
            if (handle != null && !handle.isCancelled()) {
                handle.cancel();
            }
            log.info("[gamer流式] 已取消当前 stream: game={}, memoryId={}, reason={}", gameName, memoryId, reason);
        }

        /**
         * 标记本次流式调用结束。
         */
        private void finish() {
            if (closed.compareAndSet(false, true)) {
                finished.countDown();
            }
        }

        /**
         * 截取文本尾部，避免长 reasoning 复盘过度膨胀。
         */
        private String tail(String text, int maxChars) {
            if (text == null || text.isBlank()) {
                return "";
            }
            int safeMax = Math.max(256, maxChars);
            return text.length() <= safeMax ? text.trim() : text.substring(text.length() - safeMax).trim();
        }
    }
}
