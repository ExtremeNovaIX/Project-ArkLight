package p1.component.agent.task.supervisor;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import p1.component.agent.task.checker.TaskCheckerAgent;
import p1.component.agent.task.checker.TaskCheckerVerdict;
import p1.component.agent.task.context.TaskBlackboardRenderer;
import p1.component.agent.task.context.TaskExecutionResultRenderer;
import p1.component.agent.task.context.TaskPromptSupport;
import p1.component.agent.task.context.TaskSupervisorPromptRenderer;
import p1.component.agent.task.exception.TaskCheckerException;
import p1.component.agent.task.exception.TaskSupervisorRoundLimitExceededException;
import p1.component.agent.task.factory.TaskSupervisorAiServiceFactory;
import p1.component.agent.task.state.TaskBlackboard;
import p1.component.agent.task.state.TaskExecuteResult;
import p1.component.agent.task.state.TaskSupervisorBlackboardEntry;
import p1.component.agent.task.state.TaskSupervisorRoundBudget;
import p1.component.agent.tools.MemorySearchTools;
import p1.model.ChatLogEntity;
import p1.service.ChatLogRepository;
import p1.utils.ChatMessageUtil;
import p1.utils.SessionUtil;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@RequiredArgsConstructor
@Slf4j
@Service
public class TaskSupervisorAgent {

    private static final int MAX_ROUTER_ROUNDS = 10;
    private static final int MAX_CHECKER_RETRIES = 2;
    private static final String CLAIM_WORKING = "WORKING";
    private static final String DECISION_APPROVED = "APPROVED";
    private static final String DECISION_RETRY = "RETRY";

    private final TaskSupervisorAiServiceFactory supervisorFactory;
    private final TaskCheckerAgent checker;
    private final MemorySearchTools memorySearchTools;
    private final ChatLogRepository chatLogRepository;
    private final ObjectMapper objectMapper;

    private final TaskSupervisorPromptRenderer promptRenderer = new TaskSupervisorPromptRenderer();
    private final TaskExecutionResultRenderer resultRenderer = new TaskExecutionResultRenderer();
    private final TaskBlackboardRenderer blackboardRenderer = new TaskBlackboardRenderer();

    public TaskExecuteResult handle(@NonNull String rawRequest) {
        if (!StringUtils.hasText(rawRequest)) {
            return TaskExecuteResult.failed("task-supervisor-invalid", "empty_request", "后端助手请求不能为空。");
        }

        TaskRunContext context = createRunContext(rawRequest.trim());
        try {
            return execute(context);
        } catch (TaskSupervisorRoundLimitExceededException e) {
            log.warn("[TaskSupervisor] 工具轮次超限, taskRunId={}, sessionId={}, usedRounds={}, maxRounds={}, toolName={}",
                    e.taskRunId(), context.sessionId(), context.toolbox().usedRounds(), e.maxRounds(), e.toolName());
            return TaskExecuteResult.timeout(context.taskRunId(), "supervisor_round_limit_exceeded");
        } catch (TaskCheckerException e) {
            log.error("[TaskSupervisor] checker 调用失败, taskRunId={}, sessionId={}", context.taskRunId(), context.sessionId(), e);
            return TaskExecuteResult.failed(context.taskRunId(), "checker_invocation_failed", "后端助手验收失败。");
        } catch (RuntimeException e) {
            log.error("[TaskSupervisor] supervisor 调用失败, taskRunId={}, sessionId={}", context.taskRunId(), context.sessionId(), e);
            return TaskExecuteResult.failed(context.taskRunId(), "supervisor_invocation_failed", "后端助手执行失败。");
        }
    }

    private TaskExecuteResult execute(TaskRunContext context) {
        String currentInstruction = context.agentRequest();
        int checkerAttempt = 0;
        while (true) {
            SupervisorAttempt attempt = runSupervisorAttempt(context, currentInstruction);
            if (attempt.isWorking()) {
                if (!attempt.hasProgress()) {
                    log.warn("[TaskSupervisor] supervisor 返回 WORKING 但没有推进, taskRunId={}, sessionId={}, usedRounds={}",
                            context.taskRunId(), context.sessionId(), context.toolbox().usedRounds());
                    return TaskExecuteResult.failed(context.taskRunId(), "supervisor_working_without_progress");
                }
                continue;
            }

            TaskCheckerVerdict verdict = checker.check(
                    context.blackboard(),
                    attempt.decision(),
                    context.userQuestion(),
                    context.agentRequest(),
                    currentInstruction
            );
            AttemptResolution resolution = resolveVerdict(context, verdict, checkerAttempt);
            if (resolution.isFinal()) {
                return resolution.finalResult();
            }
            checkerAttempt++;
            currentInstruction = resolution.nextInstruction();
        }
    }

    private SupervisorAttempt runSupervisorAttempt(TaskRunContext context, String currentInstruction) {
        int usedRoundsBefore = context.toolbox().usedRounds();
        int evidenceCountBefore = context.blackboard().snapshotEntries().size();

        String supervisorContext = promptRenderer.render(
                context.userQuestion(),
                context.agentRequest(),
                currentInstruction,
                blackboardRenderer.renderReadonlyView(context.blackboard())
        );
        TaskSupervisorFinalDecision decision = supervisorFactory.create(context.toolbox()).work(supervisorContext);
        if (decision == null) {
            throw new IllegalStateException("supervisor returned null decision");
        }

        int usedRoundsAfter = context.toolbox().usedRounds();
        int evidenceCountAfter = context.blackboard().snapshotEntries().size();
        return new SupervisorAttempt(
                decision,
                usedRoundsAfter > usedRoundsBefore || evidenceCountAfter != evidenceCountBefore
        );
    }

    private AttemptResolution resolveVerdict(TaskRunContext context,
                                             TaskCheckerVerdict verdict,
                                             int checkerAttempt) {
        return switch (normalizeDecision(verdict.getDecision())) {
            case DECISION_APPROVED -> AttemptResolution.finalResult(buildApprovedResult(context));
            case DECISION_RETRY -> buildRetryResolution(context, verdict, checkerAttempt);
            default -> AttemptResolution.finalResult(TaskExecuteResult.failed(context.taskRunId(), "checker_rejected"));
        };
    }

    private AttemptResolution buildRetryResolution(TaskRunContext context,
                                                   TaskCheckerVerdict verdict,
                                                   int checkerAttempt) {
        String retryInstruction = TaskPromptSupport.trimToEmpty(verdict.getRetryInstruction());
        if (!StringUtils.hasText(retryInstruction)) {
            return AttemptResolution.finalResult(TaskExecuteResult.failed(context.taskRunId(), "checker_retry_without_instruction"));
        }
        if (checkerAttempt >= MAX_CHECKER_RETRIES) {
            return AttemptResolution.finalResult(TaskExecuteResult.failed(context.taskRunId(), "checker_retry_limit_exceeded"));
        }

        log.info("[TaskSupervisor] checker 要求重试, taskRunId={}, sessionId={}, checkerAttempt={}, usedRounds={}, instruction={}",
                context.taskRunId(), context.sessionId(), checkerAttempt + 1, context.toolbox().usedRounds(), retryInstruction);
        return AttemptResolution.nextInstruction(retryInstruction);
    }

    private TaskExecuteResult buildApprovedResult(TaskRunContext context) {
        List<TaskSupervisorBlackboardEntry> visibleEntries = context.blackboard().snapshotEntries();
        List<String> visibleEvidenceIds = context.blackboard().snapshotEvidenceIds();
        String responseText = resultRenderer.renderApprovedResponse(visibleEntries);
        log.info("[TaskSupervisor] 任务完成, taskRunId={}, sessionId={}, visibleEvidenceCount={}, usedRounds={}",
                context.taskRunId(), context.sessionId(), visibleEvidenceIds.size(), context.toolbox().usedRounds());
        return TaskExecuteResult.completed(context.taskRunId(), responseText, visibleEvidenceIds);
    }

    private TaskRunContext createRunContext(String agentRequest) {
        String sessionId = TaskPromptSupport.trimToEmpty(MDC.get("sessionId"));
        String taskRunId = buildTaskRunId(sessionId);
        String userQuestion = resolveUserQuestion(sessionId).orElse(agentRequest);
        TaskBlackboard blackboard = new TaskBlackboard();
        TaskSupervisorToolbox toolbox = new TaskSupervisorToolbox(
                memorySearchTools,
                blackboard,
                new TaskSupervisorRoundBudget(taskRunId, MAX_ROUTER_ROUNDS),
                objectMapper
        );
        return new TaskRunContext(sessionId, taskRunId, userQuestion, agentRequest, blackboard, toolbox);
    }

    private Optional<String> resolveUserQuestion(@NonNull String sessionId) {
        sessionId = SessionUtil.normalizeSessionId(sessionId);
        return chatLogRepository.findFirstBySessionIdAndRoleOrderByCreatedAtDesc(sessionId, "USER")
                .map(ChatLogEntity::getContent)
                .map(ChatMessageDeserializer::messageFromJson)
                .map(ChatMessageUtil::extractText)
                .filter(StringUtils::hasText);
    }

    private String buildTaskRunId(@NonNull String sessionId) {
        String normalizedSessionId = StringUtils.hasText(sessionId) ? sessionId.trim() : "unknown";
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT).format(LocalDateTime.now());
        return "taskSupervisor." + normalizedSessionId + "." + timestamp + "." + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String normalizeDecision(String decision) {
        return TaskPromptSupport.trimToEmpty(decision).toUpperCase(Locale.ROOT);
    }

    private static String normalizeCompletionClaim(String completionClaim) {
        return TaskPromptSupport.trimToEmpty(completionClaim).toUpperCase(Locale.ROOT);
    }

    private record TaskRunContext(String sessionId,
                                  String taskRunId,
                                  String userQuestion,
                                  String agentRequest,
                                  TaskBlackboard blackboard,
                                  TaskSupervisorToolbox toolbox) {
    }

    private record SupervisorAttempt(TaskSupervisorFinalDecision decision, boolean hasProgress) {

        boolean isWorking() {
            return CLAIM_WORKING.equals(normalizeCompletionClaim(decision.getCompletionClaim()));
        }
    }

    private record AttemptResolution(TaskExecuteResult finalResult, String nextInstruction) {

        static AttemptResolution finalResult(TaskExecuteResult finalResult) {
            return new AttemptResolution(finalResult, null);
        }

        static AttemptResolution nextInstruction(String nextInstruction) {
            return new AttemptResolution(null, nextInstruction);
        }

        boolean isFinal() {
            return finalResult != null;
        }
    }
}
