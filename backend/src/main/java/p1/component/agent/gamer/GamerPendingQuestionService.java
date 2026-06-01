package p1.component.agent.gamer;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import p1.component.agent.gamer.loop.ActiveGameRegistry;
import p1.component.agent.gamer.loop.ActiveGameSession;
import p1.component.agent.gamer.loop.GameLoopObservationBackoffService;
import p1.component.agent.interaction.InteractionCoordinator;
import p1.component.agent.rp.proactive.RpProactiveSessionRegistry;
import p1.component.agent.tts.TtsSpeechService;
import p1.component.agent.tts.TtsSpeechSession;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static p1.utils.SessionUtil.normalizeSessionId;

/**
 * Manages short RP-to-user tactical questions.
 * <p>
 * This is a fast path outside the normal user reply loop: RP asks directly,
 * user replies directly, and the result is injected into the next RP turn
 * as a compact context block.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GamerPendingQuestionService {

    private static final Duration QUESTION_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration QUESTION_HOLD_TTL = Duration.ofSeconds(20);
    private static final String USER_ANSWER_MEMORY_NAME = "game_ask_user_answer";
    private static final String SYSTEM_TIMEOUT_MEMORY_NAME = "system_game_ask_timeout";

    private final ActiveGameRegistry activeGameRegistry;
    private final InteractionCoordinator interactionCoordinator;
    private final GameLoopObservationBackoffService observationBackoffService;
    private final ChatMemoryProvider chatMemoryProvider;
    private final TtsSpeechService ttsSpeechService;
    private final RpProactiveSessionRegistry sessionRegistry;
    @Qualifier("asyncTaskExecutor")
    private final Executor asyncTaskExecutor;

    private final Map<String, PendingQuestion> pendingByRpSession = new ConcurrentHashMap<>();

    /**
     * Register a tactical question emitted by RP game control.
     *
     * @param gameName  game name
     * @param sessionId game session id
     * @param json      ASK JSON object
     * @return registered question, or empty when the JSON does not contain a usable question
     */
    public Optional<PendingQuestion> registerQuestion(String gameName, String sessionId, JsonNode json) {
        return registerQuestion(gameName, sessionId, json, true);
    }

    /**
     * Register a tactical question.
     *
     * @param gameName   game id
     * @param sessionId  game session id
     * @param json       ASK JSON object
     * @param speak      true 表示由本服务投递 TTS；false 表示 RP 控制块已经说出口
     * @return registered question, or empty when the JSON does not contain a usable question
     */
    public Optional<PendingQuestion> registerQuestion(String gameName, String sessionId, JsonNode json, boolean speak) {
        String question = firstNonBlank(text(json, "question"), text(json, "message"), text(json, "speech"));
        if (question.isBlank()) {
            return Optional.empty();
        }

        ActiveGameSession session = activeGameRegistry.get(gameName, sessionId);
        String rpSessionId = session == null ? normalizeSessionId(sessionId) : normalizeSessionId(session.getRpSessionId());
        List<QuestionChoice> choices = parseChoices(json);
        String spokenText = firstNonBlank(text(json, "speech"), renderSpokenQuestion(question, choices));
        PendingQuestion pending = new PendingQuestion(
                UUID.randomUUID().toString(),
                gameName,
                sessionId,
                rpSessionId,
                question.trim(),
                spokenText.trim(),
                firstNonBlank(text(json, "reason"), text(json, "rationale")),
                choices,
                firstNonBlank(text(json, "default_choice"), text(json, "defaultChoice")),
                Instant.now(),
                Instant.now().plus(QUESTION_TIMEOUT));

        pendingByRpSession.put(rpSessionId, pending);
        interactionCoordinator.beginGameWait(rpSessionId, QUESTION_HOLD_TTL);
        appendAskMemory(pending);
        if (speak) {
            speakQuestion(pending);
        }
        scheduleTimeout(pending);
        touchSession(pending);

        log.info("[游戏询问] 已登记战术询问: game={}, session={}, rpSession={}, questionId={}, question={}",
                gameName, sessionId, rpSessionId, pending.questionId(), pending.question());
        return Optional.of(pending);
    }

    /**
     * Consume the active tactical question and return a compact context block for RP.
     *
     * @param rpSessionId RP session id
     * @param rawAnswer   user text
     * @return context block injected into the next RP turn
     */
    public Optional<String> consumeAnswerForRp(String rpSessionId, String rawAnswer) {
        String normalizedRpSessionId = normalizeSessionId(rpSessionId);
        PendingQuestion pending = pendingByRpSession.remove(normalizedRpSessionId);
        if (pending == null) {
            return Optional.empty();
        }

        String answer = rawAnswer == null ? "" : rawAnswer.trim();
        ResolvedAnswer resolved = resolveAnswer(answer, pending.choices());
        appendUserAnswerMemory(pending, answer, resolved);
        resumeGame(pending);

        log.info("[游戏询问] 已消费用户回答: game={}, session={}, rpSession={}, questionId={}, answer={}",
                pending.gameName(), pending.sessionId(), pending.rpSessionId(), pending.questionId(), answer);
        return Optional.of("""
                <game_ask_answer>
                你刚才向用户提出了战术询问，现在用户已经回答。请基于用户回答和最新游戏状态继续亲自行动，不要重复等待。
                原问题：%s
                用户原始回答：%s
                解析选择：%s
                默认选择：%s
                </game_ask_answer>
                """.formatted(
                pending.question(),
                blankToDefault(answer, "空回复"),
                resolved.choice().map(QuestionChoice::label).orElse("未匹配固定选项，请按原始回答理解"),
                blankToDefault(pending.defaultChoice(), "无")));
    }

    /**
     * Query whether a session is waiting for a tactical answer.
     *
     * @param rpSessionId RP session id
     * @return true when a question is pending
     */
    public boolean hasPendingQuestion(String rpSessionId) {
        return pendingByRpSession.containsKey(normalizeSessionId(rpSessionId));
    }

    private void scheduleTimeout(PendingQuestion pending) {
        CompletableFuture.runAsync(
                () -> timeoutIfStillPending(pending),
                CompletableFuture.delayedExecutor(QUESTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS, asyncTaskExecutor));
    }

    private void timeoutIfStillPending(PendingQuestion expected) {
        PendingQuestion current = pendingByRpSession.get(expected.rpSessionId());
        if (current == null || !current.questionId().equals(expected.questionId())) {
            return;
        }
        if (!pendingByRpSession.remove(expected.rpSessionId(), expected)) {
            return;
        }

        appendTimeoutMemory(expected);
        resumeGame(expected);
        log.info("[游戏询问] 询问超时，已解除等待并写入 RP 记忆: game={}, session={}, rpSession={}, questionId={}",
                expected.gameName(), expected.sessionId(), expected.rpSessionId(), expected.questionId());
    }

    private void resumeGame(PendingQuestion pending) {
        touchSession(pending);
        interactionCoordinator.endGameWait(pending.rpSessionId());
    }

    private void touchSession(PendingQuestion pending) {
        ActiveGameSession session = activeGameRegistry.get(pending.gameName(), pending.sessionId());
        if (session == null) {
            return;
        }
        if (session.getState() == ActiveGameSession.State.PAUSED) {
            session.setState(ActiveGameSession.State.RUNNING);
        }
        observationBackoffService.reset(session);
        session.touch();
    }

    private void speakQuestion(PendingQuestion pending) {
        try {
            TtsSpeechSession speech = ttsSpeechService.open(pending.rpSessionId(), "game-ask");
            speech.accept(pending.spokenText());
            speech.finish();
            sessionRegistry.observeRpSpeech(pending.rpSessionId());
        } catch (Exception e) {
            log.warn("[游戏询问] 战术询问 TTS 投递失败: rpSession={}, questionId={}, reason={}",
                    pending.rpSessionId(), pending.questionId(), e.getMessage());
        }
    }

    private void appendAskMemory(PendingQuestion pending) {
        appendAiMemory(pending.rpSessionId(), """
                【游戏队友询问】
                我向用户发起了一个战术确认，不是 RP 闲聊。
                问题：%s
                选项：%s
                原因：%s
                """.formatted(
                pending.question(),
                pending.choices().isEmpty() ? "无固定选项" : renderChoices(pending.choices()),
                blankToDefault(pending.reason(), "未说明")));
    }

    private void appendUserAnswerMemory(PendingQuestion pending, String answer, ResolvedAnswer resolved) {
        appendUserMemory(pending.rpSessionId(), USER_ANSWER_MEMORY_NAME, """
                【用户回答游戏队友询问】
                问题：%s
                用户回答：%s
                解析选择：%s
                """.formatted(
                pending.question(),
                blankToDefault(answer, "空回复"),
                resolved.choice().map(QuestionChoice::label).orElse("未匹配固定选项")));
    }

    private void appendTimeoutMemory(PendingQuestion pending) {
        appendUserMemory(pending.rpSessionId(), SYSTEM_TIMEOUT_MEMORY_NAME, """
                【游戏队友询问超时】
                问题：%s
                系统结果：15 秒内没有收到用户回复；你需要基于最新状态和默认判断继续，不再等待。
                """.formatted(pending.question()));
    }

    private void appendAiMemory(String rpSessionId, String text) {
        try {
            ChatMemory memory = chatMemoryProvider.get(normalizeSessionId(rpSessionId));
            if (memory != null) {
                memory.add(AiMessage.from(text.trim()));
            }
        } catch (Exception e) {
            log.warn("[游戏询问] 写入 RP AI 记忆失败: rpSession={}, reason={}", rpSessionId, e.getMessage());
        }
    }

    private void appendUserMemory(String rpSessionId, String name, String text) {
        try {
            ChatMemory memory = chatMemoryProvider.get(normalizeSessionId(rpSessionId));
            if (memory != null) {
                memory.add(UserMessage.from(name, text.trim()));
            }
        } catch (Exception e) {
            log.warn("[游戏询问] 写入 RP 用户记忆失败: rpSession={}, reason={}", rpSessionId, e.getMessage());
        }
    }

    private ResolvedAnswer resolveAnswer(String answer, List<QuestionChoice> choices) {
        if (choices == null || choices.isEmpty() || answer == null || answer.isBlank()) {
            return new ResolvedAnswer(Optional.empty());
        }
        String normalizedAnswer = normalizeAnswer(answer);
        Optional<QuestionChoice> byNumber = resolveNumberedChoice(normalizedAnswer, choices);
        if (byNumber.isPresent()) {
            return new ResolvedAnswer(byNumber);
        }
        return new ResolvedAnswer(choices.stream()
                .filter(choice -> answerMatchesChoice(normalizedAnswer, choice))
                .findFirst());
    }

    private Optional<QuestionChoice> resolveNumberedChoice(String normalizedAnswer, List<QuestionChoice> choices) {
        int index = switch (normalizedAnswer) {
            case "1", "一", "第一个", "选一", "第一项" -> 0;
            case "2", "二", "第二个", "选二", "第二项" -> 1;
            case "3", "三", "第三个", "选三", "第三项" -> 2;
            default -> -1;
        };
        if (index >= 0 && index < choices.size()) {
            return Optional.of(choices.get(index));
        }
        return Optional.empty();
    }

    private boolean answerMatchesChoice(String normalizedAnswer, QuestionChoice choice) {
        String id = normalizeAnswer(choice.id());
        String label = normalizeAnswer(choice.label());
        return (!id.isBlank() && (normalizedAnswer.equals(id) || normalizedAnswer.contains(id) || id.contains(normalizedAnswer)))
                || (!label.isBlank() && (normalizedAnswer.equals(label) || normalizedAnswer.contains(label) || label.contains(normalizedAnswer)));
    }

    private List<QuestionChoice> parseChoices(JsonNode json) {
        JsonNode node = json == null ? null : firstExisting(json.path("choices"), json.path("options"));
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<QuestionChoice> choices = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || item.isNull()) {
                continue;
            }
            if (item.isTextual()) {
                String label = item.asText("").trim();
                if (!label.isBlank()) {
                    choices.add(new QuestionChoice(label, label));
                }
                continue;
            }
            if (item.isObject()) {
                String label = firstNonBlank(text(item, "label"), text(item, "text"), text(item, "name"));
                String id = firstNonBlank(text(item, "id"), label);
                if (!label.isBlank()) {
                    choices.add(new QuestionChoice(id, label));
                }
            }
        }
        return choices;
    }

    private JsonNode firstExisting(JsonNode first, JsonNode second) {
        if (first != null && !first.isMissingNode() && !first.isNull()) {
            return first;
        }
        return second;
    }

    private String renderSpokenQuestion(String question, List<QuestionChoice> choices) {
        if (choices == null || choices.isEmpty()) {
            return question;
        }
        return question + " 选项：" + renderChoices(choices);
    }

    private String renderChoices(List<QuestionChoice> choices) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < choices.size(); i++) {
            if (i > 0) {
                sb.append(" / ");
            }
            sb.append(choices.get(i).label());
        }
        return sb.toString();
    }

    private String text(JsonNode json, String field) {
        if (json == null || field == null || field.isBlank()) {
            return "";
        }
        return json.path(field).asText("").trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String normalizeAnswer(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim().toLowerCase();
    }

    public record PendingQuestion(
            String questionId,
            String gameName,
            String sessionId,
            String rpSessionId,
            String question,
            String spokenText,
            String reason,
            List<QuestionChoice> choices,
            String defaultChoice,
            Instant createdAt,
            Instant expiresAt
    ) {
    }

    public record QuestionChoice(String id, String label) {
    }

    private record ResolvedAnswer(Optional<QuestionChoice> choice) {
    }
}
