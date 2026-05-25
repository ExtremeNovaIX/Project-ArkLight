package p1.component.agent.task;

import org.junit.jupiter.api.Test;
import p1.component.agent.task.context.TaskBlackboardRenderer;
import p1.component.agent.task.context.TaskCheckerPromptRenderer;
import p1.component.agent.task.context.TaskSupervisorPromptRenderer;
import p1.component.agent.task.state.TaskBlackboard;
import p1.component.agent.task.supervisor.TaskSupervisorFinalDecision;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskRenderersTest {

    @Test
    void shouldRenderSupervisorAndCheckerPromptsWithStableSections() {
        TaskSupervisorPromptRenderer supervisorPromptRenderer = new TaskSupervisorPromptRenderer();
        String supervisorPrompt = supervisorPromptRenderer.render(
                "user question",
                "agent request",
                "agent request",
                "board view"
        );

        assertTrue(supervisorPrompt.contains("<task_goal>"));
        assertTrue(supervisorPrompt.contains("<blackboard_readonly>"));
        assertFalse(supervisorPrompt.contains("<current_instruction>"));

        TaskSupervisorFinalDecision decision = new TaskSupervisorFinalDecision();
        decision.setCompletionClaim("COMPLETED");
        decision.setCandidateEvidenceIds(List.of("method.searchLongTermMemory#01"));
        decision.setOpenRisks(List.of("risk"));

        TaskCheckerPromptRenderer checkerPromptRenderer = new TaskCheckerPromptRenderer();
        String checkerPrompt = checkerPromptRenderer.render(
                "user question",
                "agent request",
                "follow up",
                "index view",
                "candidate view",
                decision,
                List.of("method.searchLongTermMemory#01")
        );

        assertTrue(checkerPrompt.contains("<blackboard_evidence_index>"));
        assertTrue(checkerPrompt.contains("<candidate_evidence>"));
        assertTrue(checkerPrompt.contains("candidateEvidenceIds=[method.searchLongTermMemory#01]"));
        assertTrue(checkerPrompt.contains("<current_instruction>"));
    }

    @Test
    void shouldRenderBlackboardViewsWithoutLeakingRawPayloads() {
        TaskBlackboard blackboard = new TaskBlackboard();
        blackboard.appendMethodSuccess(
                "searchLongTermMemory",
                "childhood trip",
                "status=OK\nmessage=found hit",
                "{\"raw\":true}"
        );
        blackboard.appendMethodSuccess(
                "searchLongTermMemory",
                "childhood trip",
                "status=OK\nmessage=found hit",
                "{\"raw\":true}"
        );

        TaskBlackboardRenderer renderer = new TaskBlackboardRenderer();
        String readonlyView = renderer.renderReadonlyView(blackboard);
        String indexView = renderer.renderEvidenceIndexView(blackboard);

        assertTrue(readonlyView.contains("[evidenceId=method.searchLongTermMemory#01]"));
        assertTrue(readonlyView.contains("status=OK"));
        assertFalse(readonlyView.contains("{\"raw\":true}"));
        assertTrue(indexView.contains("reusedExistingEvidenceCount=1"));
    }

    @Test
    void shouldHideRemovedEvidenceFromRenderedViews() {
        TaskBlackboard blackboard = new TaskBlackboard();
        blackboard.appendMethodSuccess(
                "searchLongTermMemory",
                "childhood trip",
                "status=OK\nmessage=found childhood trip",
                "{\"raw\":true}"
        );
        blackboard.appendMethodSuccess(
                "searchLongTermMemory",
                "relationship update",
                "status=OK\nmessage=found relationship update",
                "{\"raw\":false}"
        );
        blackboard.removeEvidence("method.searchLongTermMemory#01");

        TaskBlackboardRenderer renderer = new TaskBlackboardRenderer();
        String readonlyView = renderer.renderReadonlyView(blackboard);
        String indexView = renderer.renderEvidenceIndexView(blackboard);

        assertFalse(readonlyView.contains("method.searchLongTermMemory#01"));
        assertTrue(readonlyView.contains("method.searchLongTermMemory#02"));
        assertFalse(indexView.contains("method.searchLongTermMemory#01"));
        assertTrue(indexView.contains("method.searchLongTermMemory#02"));
    }
}
