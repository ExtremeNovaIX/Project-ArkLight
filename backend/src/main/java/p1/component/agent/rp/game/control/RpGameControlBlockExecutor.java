package p1.component.agent.rp.game.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.CustomLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import p1.component.agent.gamer.GamerPendingQuestionService;
import p1.component.agent.gamer.loop.ActiveGameRegistry;
import p1.component.agent.gamer.loop.ActiveGameSession;
import p1.component.agent.gamer.trace.GamerDecisionTraceService;

import java.util.Optional;

import static p1.utils.SessionUtil.normalizeSessionId;

/**
 * 执行 RP 控制块。
 */
@Service
@RequiredArgsConstructor
@CustomLog
public class RpGameControlBlockExecutor {

    private final ActiveGameRegistry activeGameRegistry;
    private final RpActionParserService actionParserService;
    private final GamerPendingQuestionService pendingQuestionService;
    private final GamerDecisionTraceService traceService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public boolean hasActiveGame(String rpSessionId) {
        return activeGameRegistry.findBySessionId(normalizeSessionId(rpSessionId)).isPresent();
    }

    /**
     * 执行一个 RP 控制块。
     *
     * @param rpSessionId RP 会话 id
     * @param block       控制块
     * @return 执行反馈；无需反馈时为空
     */
    public Optional<String> execute(String rpSessionId, RpControlBlock block) {
        if (block == null) {
            return Optional.empty();
        }
        Optional<ActiveGameSession> active = activeGameRegistry.findBySessionId(normalizeSessionId(rpSessionId));
        if (active.isEmpty()) {
            return Optional.empty();
        }
        ActiveGameSession session = active.get();
        traceService.appendRpControlBlockTrace(
                session.getGameName(),
                session.getSessionId(),
                block);
        if (block.isAsk()) {
            return registerAsk(session, block);
        }
        if (block.isCommittedAction()) {
            return executeAction(session, block);
        }
        return Optional.empty();
    }

    private Optional<String> executeAction(ActiveGameSession session, RpControlBlock block) {
        if (!block.isCommittedAction()) {
            return Optional.empty();
        }
        try {
            String result = actionParserService.execute(session.getGameName(), session.getSessionId(), block.doText());
            return Optional.of(result);
        } catch (RuntimeException e) {
            traceService.appendRpCommandFailureTrace(
                    session.getGameName(),
                    session.getSessionId(),
                    block.doText(),
                    e.getMessage());
            throw e;
        }
    }

    private Optional<String> registerAsk(ActiveGameSession session, RpControlBlock block) {
        String question = firstNonBlank(block.doText(), block.say());
        if (question.isBlank()) {
            return Optional.empty();
        }
        ObjectNode json = objectMapper.createObjectNode();
        json.put("type", "ask");
        json.put("question", question);
        if (block.hasSpeech()) {
            json.put("speech", block.say());
        }
        pendingQuestionService.registerQuestion(session.getGameName(), session.getSessionId(), json, false);
        return Optional.of("已登记 RP 战术询问：" + question);
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
}
