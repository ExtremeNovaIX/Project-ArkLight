package p1.component.agent.task.context;

import p1.component.agent.task.supervisor.TaskSupervisorFinalDecision;

import java.util.List;

public final class TaskCheckerPromptRenderer {

    public String render(String userQuestion,
                         String agentRequest,
                         String currentInstruction,
                         String evidenceIndexView,
                         String candidateEvidenceView,
                         TaskSupervisorFinalDecision decision,
                         List<String> candidateEvidenceIds) {
        return """
                %s
                <blackboard_evidence_index>
                %s
                </blackboard_evidence_index>
                <candidate_evidence>
                %s
                </candidate_evidence>
                <supervisor_final_decision>
                completionClaim=%s
                candidateEvidenceIds=%s
                openRisks=%s
                </supervisor_final_decision>
                %s
                """.formatted(
                TaskPromptSupport.renderTaskGoal(userQuestion, agentRequest),
                evidenceIndexView,
                candidateEvidenceView,
                TaskPromptSupport.trimToEmpty(decision.getCompletionClaim()),
                TaskPromptSupport.formatList(candidateEvidenceIds),
                TaskPromptSupport.formatList(decision.payloadOpenRisks()),
                TaskPromptSupport.renderCurrentInstructionSection(agentRequest, currentInstruction)
        );
    }
}
