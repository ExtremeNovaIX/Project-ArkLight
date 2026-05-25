package p1.component.agent.task.checker;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import p1.component.agent.task.context.TaskBlackboardRenderer;
import p1.component.agent.task.context.TaskCheckerPromptRenderer;
import p1.component.agent.task.exception.TaskCheckerException;
import p1.component.agent.task.state.TaskBlackboard;
import p1.component.agent.task.supervisor.TaskSupervisorFinalDecision;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskCheckerAgent {

    private final TaskCheckerAiService checker;

    private final TaskCheckerPromptRenderer promptRenderer = new TaskCheckerPromptRenderer();
    private final TaskBlackboardRenderer blackboardRenderer = new TaskBlackboardRenderer();

    public TaskCheckerVerdict check(@NonNull TaskBlackboard blackboard,
                                    @NonNull TaskSupervisorFinalDecision decision,
                                    @NonNull String userQuestion,
                                    @NonNull String agentRequest,
                                    @NonNull String currentInstruction) {
        try {
            List<String> candidateEvidenceIds = blackboard.filterExistingIds(decision.payloadCandidateEvidenceIds());
            String checkerContext = promptRenderer.render(
                    userQuestion,
                    agentRequest,
                    currentInstruction,
                    blackboardRenderer.renderEvidenceIndexView(blackboard),
                    blackboardRenderer.renderEvidenceView(blackboard, candidateEvidenceIds),
                    decision,
                    candidateEvidenceIds
            );
            TaskCheckerVerdict verdict = checker.check(checkerContext);
            if (verdict == null) {
                throw new TaskCheckerException("checker returned empty verdict");
            }
            return verdict;
        } catch (TaskCheckerException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new TaskCheckerException("checker invocation failed", e);
        }
    }
}
