package p1.component.agent.rp.game.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.service.TokenStream;
import lombok.CustomLog;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import p1.infrastructure.logging.LogDomain;
import p1.infrastructure.logging.LogOutcome;
import p1.component.agent.gamer.GameSessionKey;
import p1.component.agent.gamer.bridge.GameAvailableOperations;
import p1.component.agent.gamer.bridge.GameBridgeExecutionException;
import p1.component.agent.gamer.bridge.GameBridgeService;
import p1.component.agent.gamer.trace.GamerDecisionTraceService;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 将 RP 给出的具体自然语言动作翻译为 MCP 操作，并提交给游戏桥接层执行。
 */
@Service
@RequiredArgsConstructor
@CustomLog
public class RpActionParserService {

    private static final long PARSER_STREAM_WAIT_SECONDS = 120L;

    private final RpActionParserAgent parserAgent;
    private final ObjectProvider<GameBridgeService> bridgeServiceProvider;
    private final GamerDecisionTraceService traceService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, ExecutionOrderState> executionOrderBySession = new ConcurrentHashMap<>();

    public String execute(String gameName, String sessionId, String rpDo) {
        if (rpDo == null || rpDo.isBlank()) {
            return "";
        }
        String executionKey = GameSessionKey.of(gameName, sessionId);
        long executionTicket = reserveExecutionTicket(executionKey);
        GameBridgeService bridgeService = bridgeServiceProvider.getObject();
        ObjectNode action;
        String raw = "";
        try {
            GameAvailableOperations availableOperations = bridgeService.describeAvailableOperations(gameName, sessionId);
            ParserParseResult parsed = parseStreaming(parserAgent.parse(renderParserInput(
                    rpDo,
                    availableOperations.detailed(),
                    availableOperations.currentStateJson())), availableOperations.toolNames());
            raw = parsed.raw();
            action = parsed.action();
            action.put("_rp_do", rpDo.trim());
            action.put("_parser_raw", raw == null ? "" : raw.trim());
            action.put("_parser_latency_ms", parsed.latencyMs());
            action.put("_parser_early_completed", parsed.earlyCompleted());
            action.put("_parser_early_cancelled", parsed.earlyCancelled());
        } catch (ParserFailureException e) {
            raw = e.rawOutput().isBlank() ? raw : e.rawOutput();
            completeExecutionTicket(executionKey, executionTicket);
            traceService.appendActionParserFailureTrace(gameName, executionKey, rpDo, raw, e.getMessage());
            log.warn(LogDomain.GAME, "action.parser_failed", LogOutcome.DEGRADED, "gameName", gameName, "sessionId", sessionId, "rpDo", rpDo, "reason", e.getMessage());
            throw new RpGameActionExecutionException("RP 动作解析失败，未执行动作：" + e.getMessage(), e);
        } catch (Exception e) {
            completeExecutionTicket(executionKey, executionTicket);
            traceService.appendActionParserFailureTrace(gameName, executionKey, rpDo, raw, e.getMessage());
            log.warn(LogDomain.GAME, "action.parser_invocation_failed", LogOutcome.DEGRADED, e, "gameName", gameName, "sessionId", sessionId, "rpDo", rpDo, "reason", e.getMessage());
            throw new RpGameActionExecutionException("RP 动作解析器调用失败，未执行动作：" + e.getMessage(), e);
        }

        action.put("_ignore_rp_speaking", true);
        String arguments = action.toString();
        log.info(LogDomain.GAME, "action.parser_completed", LogOutcome.SUCCEEDED, "gameName", gameName, "sessionId", sessionId, "rpDo", rpDo, "arguments", arguments);
        try {
            return executeInOrder(executionKey, executionTicket,
                    () -> bridgeService.executeOperationQueue(gameName, sessionId, arguments));
        } catch (GameBridgeExecutionException e) {
            RpGameActionExecutionException.Kind kind = e.interactionDeferred()
                    ? RpGameActionExecutionException.Kind.INTERACTION_DEFERRED
                    : RpGameActionExecutionException.Kind.FAILURE;
            throw new RpGameActionExecutionException(e.feedback(), kind, e);
        }
    }

    private String renderParserInput(String rpDo, String availableOperations, String currentStateJson) {
        return """
                <allowed_operations>
                %s
                </allowed_operations>

                <current_game_state_json>
                %s
                </current_game_state_json>

                <rp_do>
                %s
                </rp_do>
                """.formatted(
                availableOperations == null || availableOperations.isBlank()
                        ? "(没有可用操作工具)"
                        : availableOperations.trim(),
                currentStateJson == null || currentStateJson.isBlank()
                        ? "{}"
                        : currentStateJson.trim(),
                rpDo.trim()).trim();
    }

    private ParserParseResult parseStreaming(TokenStream stream, Set<String> allowedToolNames) {
        if (stream == null) {
            throw new ParserFailureException("解析器没有返回流式响应");
        }
        long startNanos = System.nanoTime();
        ParserStreamCollector collector = new ParserStreamCollector(allowedToolNames);
        stream.onPartialResponseWithContext(collector::onPartialResponse)
                .onCompleteResponse(collector::onCompleteResponse)
                .onError(collector::onError)
                .start();
        return collector.await(startNanos);
    }

    private long reserveExecutionTicket(String executionKey) {
        ExecutionOrderState state = executionOrderBySession.computeIfAbsent(executionKey, ignored -> new ExecutionOrderState());
        synchronized (state) {
            return state.nextTicket++;
        }
    }

    private String executeInOrder(String executionKey, long ticket, Supplier<String> action) {
        ExecutionOrderState state = executionOrderBySession.computeIfAbsent(executionKey, ignored -> new ExecutionOrderState());
        synchronized (state) {
            while (ticket != state.nextToRun) {
                try {
                    state.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    completeExecutionTicket(executionKey, ticket);
                    throw new RpGameActionExecutionException("等待前序游戏动作执行被中断。", e);
                }
            }
        }
        try {
            return action.get();
        } finally {
            completeExecutionTicket(executionKey, ticket);
        }
    }

    private void completeExecutionTicket(String executionKey, long ticket) {
        ExecutionOrderState state = executionOrderBySession.computeIfAbsent(executionKey, ignored -> new ExecutionOrderState());
        synchronized (state) {
            if (ticket < state.nextToRun) {
                return;
            }
            state.completedTickets.add(ticket);
            while (state.completedTickets.remove(state.nextToRun)) {
                state.nextToRun++;
            }
            state.notifyAll();
        }
    }

    private ObjectNode parseAction(String raw, Set<String> allowedToolNames) {
        JsonNode node;
        try {
            node = objectMapper.readTree(extractJson(raw));
        } catch (Exception e) {
            throw new ParserFailureException("解析器返回非 JSON: " + preview(raw), e);
        }
        if (node == null || !node.isObject()) {
            throw new ParserFailureException("解析器未返回 JSON 对象: " + preview(raw));
        }

        ObjectNode object = (ObjectNode) node;
        JsonNode operations = object.path("operations");
        String reason = object.path("reason").asText("");
        if (!operations.isArray()) {
            throw new ParserFailureException("解析器未返回 operations 数组");
        }
        if (operations.isEmpty()) {
            throw new ParserFailureException(reason.isBlank()
                    ? "解析器无法把 RP 指令翻译成可执行操作"
                    : "解析器无法把 RP 指令翻译成可执行操作：" + reason);
        }
        validateToolNames(operations, allowedToolNames);
        if (!reason.isBlank()) {
            log.debug("[RP动作解析] parser reason={}", reason);
        }
        ObjectNode sanitized = objectMapper.createObjectNode();
        sanitized.set("operations", operations);
        return sanitized;
    }

    private ObjectNode tryParseExecutableOperations(String raw, Set<String> allowedToolNames) {
        String operationsJson = extractClosedOperationsArray(raw);
        if (operationsJson == null || operationsJson.isBlank()) {
            return null;
        }
        JsonNode operations;
        try {
            operations = objectMapper.readTree(operationsJson);
        } catch (Exception e) {
            return null;
        }
        if (!operations.isArray() || operations.isEmpty()) {
            return null;
        }
        validateToolNames(operations, allowedToolNames);
        ObjectNode sanitized = objectMapper.createObjectNode();
        sanitized.set("operations", operations);
        return sanitized;
    }

    private String extractClosedOperationsArray(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int fieldStart = findJsonField(raw, "operations");
        if (fieldStart < 0) {
            return null;
        }
        int colon = findNextColonOutsideString(raw, fieldStart);
        if (colon < 0) {
            return null;
        }
        int arrayStart = findNextNonWhitespace(raw, colon + 1);
        if (arrayStart < 0 || raw.charAt(arrayStart) != '[') {
            return null;
        }
        int arrayEnd = findMatchingBracket(raw, arrayStart, '[', ']');
        if (arrayEnd < 0) {
            return null;
        }
        return raw.substring(arrayStart, arrayEnd + 1);
    }

    private int findJsonField(String raw, String fieldName) {
        boolean inString = false;
        boolean escaped = false;
        String quotedField = "\"" + fieldName + "\"";
        for (int i = 0; i <= raw.length() - quotedField.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                if (raw.startsWith(quotedField, i) && isJsonFieldName(raw, i + quotedField.length())) {
                    return i;
                }
                inString = true;
            }
        }
        return -1;
    }

    private boolean isJsonFieldName(String raw, int afterFieldName) {
        int next = findNextNonWhitespace(raw, afterFieldName);
        return next >= 0 && raw.charAt(next) == ':';
    }

    private int findNextColonOutsideString(String raw, int start) {
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == ':') {
                return i;
            }
        }
        return -1;
    }

    private int findNextNonWhitespace(String raw, int start) {
        for (int i = start; i < raw.length(); i++) {
            if (!Character.isWhitespace(raw.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private int findMatchingBracket(String raw, int start, char open, char close) {
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void validateToolNames(JsonNode operations, Set<String> allowedToolNames) {
        if (allowedToolNames == null || allowedToolNames.isEmpty()) {
            throw new ParserFailureException("当前状态没有可用操作工具，拒绝执行 parser 输出");
        }
        for (JsonNode item : operations) {
            String tool = item.path("tool").asText("");
            if (tool.isBlank()) {
                throw new ParserFailureException("parser 输出的 operation 缺少 tool 字段: " + item);
            }
            if (!allowedToolNames.contains(tool)) {
                throw new ParserFailureException("parser 输出了不在白名单内的工具: "
                        + tool + "；当前可用工具=" + String.join(", ", allowedToolNames));
            }
        }
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return "{}";
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }

    private String preview(String raw) {
        if (raw == null) {
            return "null";
        }
        String trimmed = raw.trim();
        return trimmed.substring(0, Math.min(trimmed.length(), 200));
    }

    private static final class ParserFailureException extends RuntimeException {
        private final String rawOutput;

        private ParserFailureException(String message) {
            this(message, null, "");
        }

        private ParserFailureException(String message, Throwable cause) {
            this(message, cause, "");
        }

        private ParserFailureException(String message, Throwable cause, String rawOutput) {
            super(message, cause);
            this.rawOutput = rawOutput == null ? "" : rawOutput;
        }

        private String rawOutput() {
            return rawOutput;
        }

        private ParserFailureException withRaw(String raw) {
            if (!rawOutput.isBlank() || raw == null || raw.isBlank()) {
                return this;
            }
            return new ParserFailureException(getMessage(), getCause(), raw);
        }
    }

    private record ParserParseResult(
            ObjectNode action,
            String raw,
            long latencyMs,
            boolean earlyCompleted,
            boolean earlyCancelled
    ) {
    }

    private final class ParserStreamCollector {
        private final Set<String> allowedToolNames;
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicReference<StreamingHandle> streamingHandle = new AtomicReference<>();
        private final StringBuilder raw = new StringBuilder();
        private volatile ObjectNode action;
        private volatile Throwable error;
        private volatile boolean earlyCompleted;
        private volatile boolean earlyCancelled;

        private ParserStreamCollector(Set<String> allowedToolNames) {
            this.allowedToolNames = allowedToolNames;
        }

        private synchronized void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
            if (closed.get() || partialResponse == null) {
                return;
            }
            rememberHandle(context == null ? null : context.streamingHandle());
            String text = partialResponse.text();
            if (text == null || text.isEmpty()) {
                return;
            }
            raw.append(text);
            tryCompleteWithOperations();
        }

        private synchronized void onCompleteResponse(ChatResponse ignored) {
            if (closed.get()) {
                return;
            }
            try {
                action = parseAction(raw.toString(), allowedToolNames);
            } catch (Throwable throwable) {
                error = throwable;
            }
            finish();
        }

        private synchronized void onError(Throwable throwable) {
            if (closed.get()) {
                return;
            }
            error = throwable;
            finish();
        }

        private void tryCompleteWithOperations() {
            try {
                ObjectNode parsed = tryParseExecutableOperations(raw.toString(), allowedToolNames);
                if (parsed == null) {
                    return;
                }
                action = parsed;
                earlyCompleted = true;
                cancelStream();
                finish();
            } catch (ParserFailureException e) {
                error = e;
                cancelStream();
                finish();
            }
        }

        private ParserParseResult await(long startNanos) {
            try {
                boolean done = finished.await(PARSER_STREAM_WAIT_SECONDS, TimeUnit.SECONDS);
                if (!done) {
                    cancelStream();
                    throw new ParserFailureException("解析器流式响应超时", null, raw.toString());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelStream();
                throw new ParserFailureException("等待解析器流式响应被中断", e, raw.toString());
            }
            if (error instanceof ParserFailureException parserFailureException) {
                throw parserFailureException.withRaw(raw.toString());
            }
            if (error != null) {
                throw new ParserFailureException("解析器流式响应异常: " + error.getMessage(), error, raw.toString());
            }
            if (action == null) {
                throw new ParserFailureException("解析器没有返回可执行 JSON", null, raw.toString());
            }
            long latencyMs = Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
            return new ParserParseResult(action, raw.toString(), latencyMs, earlyCompleted, earlyCancelled);
        }

        private void rememberHandle(StreamingHandle handle) {
            if (handle != null) {
                streamingHandle.compareAndSet(null, handle);
            }
        }

        private void cancelStream() {
            StreamingHandle handle = streamingHandle.get();
            if (handle != null && !handle.isCancelled()) {
                handle.cancel();
                earlyCancelled = true;
            }
        }

        private void finish() {
            if (closed.compareAndSet(false, true)) {
                finished.countDown();
            }
        }
    }

    private static final class ExecutionOrderState {
        private long nextTicket;
        private long nextToRun;
        private final Set<Long> completedTickets = new HashSet<>();
    }
}
