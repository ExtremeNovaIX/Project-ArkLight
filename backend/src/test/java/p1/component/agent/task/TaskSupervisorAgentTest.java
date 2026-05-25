package p1.component.agent.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import p1.component.agent.task.checker.TaskCheckerAgent;
import p1.component.agent.task.checker.TaskCheckerAiService;
import p1.component.agent.task.checker.TaskCheckerVerdict;
import p1.component.agent.task.factory.TaskSupervisorAiServiceFactory;
import p1.component.agent.task.state.TaskBlackboard;
import p1.component.agent.task.state.TaskExecuteResult;
import p1.component.agent.task.supervisor.TaskSupervisorAgent;
import p1.component.agent.task.supervisor.TaskSupervisorFinalDecision;
import p1.component.agent.tools.MemorySearchTools;
import p1.model.ChatLogEntity;
import p1.service.ChatLogRepository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class TaskSupervisorAgentTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();

    @Test
    void shouldReturnApprovedEvidenceWhenCheckerApproves() {
        MemorySearchTools memorySearchTools = mock(MemorySearchTools.class);
        when(memorySearchTools.searchLongTermMemory("childhood trip"))
                .thenReturn(searchResult("We went to the beach together as kids."));

        TaskSupervisorAiServiceFactory supervisorFactory = toolbox -> context -> {
            toolbox.searchLongTermMemory("childhood trip");
            TaskSupervisorFinalDecision decision = new TaskSupervisorFinalDecision();
            decision.setCompletionClaim("COMPLETED");
            decision.setCandidateEvidenceIds(List.of("method.searchLongTermMemory#01"));
            decision.setOpenRisks(List.of());
            return decision;
        };

        TaskCheckerAiService checker = context -> approvedVerdict();

        ChatLogRepository chatLogRepository = mock(ChatLogRepository.class);
        when(chatLogRepository.findFirstBySessionIdAndRoleOrderByCreatedAtDesc(anyString(), eq("USER")))
                .thenReturn(Optional.of(userLog("Do you remember our childhood trip?")));

        TaskExecuteResult result = newAgent(supervisorFactory, checker, memorySearchTools, chatLogRepository)
                .handle("Please verify the childhood trip detail");

        assertEquals(TaskExecuteResult.Status.COMPLETED, result.status());
        assertEquals(List.of("method.searchLongTermMemory#01"), result.visibleEvidenceIds());
        assertTrue(result.responseText().contains("method.searchLongTermMemory#01"));
        assertTrue(result.responseText().contains("\"status\":\"OK\""));
    }

    @Test
    void shouldExposeRemainingBlackboardEntriesWithoutCheckerEvidenceSelection() {
        MemorySearchTools memorySearchTools = mock(MemorySearchTools.class);
        when(memorySearchTools.searchLongTermMemory("childhood trip"))
                .thenReturn(searchResult("We went to the beach together as kids."));

        TaskSupervisorAiServiceFactory supervisorFactory = toolbox -> context -> {
            toolbox.searchLongTermMemory("childhood trip");
            TaskSupervisorFinalDecision decision = new TaskSupervisorFinalDecision();
            decision.setCompletionClaim("COMPLETED");
            decision.setCandidateEvidenceIds(List.of());
            decision.setOpenRisks(List.of());
            return decision;
        };

        TaskCheckerAiService checker = context -> {
            TaskCheckerVerdict verdict = new TaskCheckerVerdict();
            verdict.setDecision("APPROVED");
            verdict.setReason("Evidence is sufficient.");
            return verdict;
        };

        ChatLogRepository chatLogRepository = mock(ChatLogRepository.class);
        TaskExecuteResult result = newAgent(supervisorFactory, checker, memorySearchTools, chatLogRepository)
                .handle("Please verify the childhood trip detail");

        assertEquals(TaskExecuteResult.Status.COMPLETED, result.status());
        assertEquals(List.of("method.searchLongTermMemory#01"), result.visibleEvidenceIds());
        assertTrue(result.responseText().contains("method.searchLongTermMemory#01"));
    }

    @Test
    void shouldReturnTimeoutWhenSupervisorExceedsRoundBudget() {
        MemorySearchTools memorySearchTools = mock(MemorySearchTools.class);
        when(memorySearchTools.searchLongTermMemory(anyString())).thenReturn(searchResult("ok"));

        TaskSupervisorAiServiceFactory supervisorFactory = toolbox -> context -> {
            for (int index = 0; index < 11; index++) {
                toolbox.searchLongTermMemory("query-" + index);
            }
            TaskSupervisorFinalDecision decision = new TaskSupervisorFinalDecision();
            decision.setCompletionClaim("COMPLETED");
            decision.setCandidateEvidenceIds(List.of());
            decision.setOpenRisks(List.of());
            return decision;
        };

        TaskCheckerAiService checker = mock(TaskCheckerAiService.class);
        ChatLogRepository chatLogRepository = mock(ChatLogRepository.class);

        TaskExecuteResult result = newAgent(supervisorFactory, checker, memorySearchTools, chatLogRepository)
                .handle("Keep trying forever");

        assertEquals(TaskExecuteResult.Status.TIMEOUT, result.status());
        assertEquals("supervisor_round_limit_exceeded", result.reasonCode());
        verifyNoInteractions(checker);
    }

    @Test
    void shouldRetrySupervisorWhenCheckerRequestsAnotherStep() {
        MemorySearchTools memorySearchTools = mock(MemorySearchTools.class);
        when(memorySearchTools.searchLongTermMemory("follow up on relationship progress"))
                .thenReturn(searchResult("They reconnected later and resumed contact."));

        AtomicInteger supervisorActivation = new AtomicInteger();
        TaskSupervisorAiServiceFactory supervisorFactory = toolbox -> context -> {
            if (supervisorActivation.getAndIncrement() == 0) {
                TaskSupervisorFinalDecision decision = new TaskSupervisorFinalDecision();
                decision.setCompletionClaim("COMPLETED");
                decision.setCandidateEvidenceIds(List.of());
                decision.setOpenRisks(List.of("Missing later relationship progress."));
                return decision;
            }

            toolbox.searchLongTermMemory("follow up on relationship progress");
            TaskSupervisorFinalDecision decision = new TaskSupervisorFinalDecision();
            decision.setCompletionClaim("COMPLETED");
            decision.setCandidateEvidenceIds(List.of("method.searchLongTermMemory#01"));
            decision.setOpenRisks(List.of());
            return decision;
        };

        AtomicInteger checkerCall = new AtomicInteger();
        TaskCheckerAiService checker = context -> {
            if (checkerCall.getAndIncrement() == 0) {
                TaskCheckerVerdict verdict = new TaskCheckerVerdict();
                verdict.setDecision("RETRY");
                verdict.setRetryInstruction("Check what happened to their relationship later.");
                verdict.setReason("Current evidence is insufficient.");
                return verdict;
            }
            return approvedVerdict();
        };

        ChatLogRepository chatLogRepository = mock(ChatLogRepository.class);
        TaskExecuteResult result = newAgent(supervisorFactory, checker, memorySearchTools, chatLogRepository)
                .handle("Confirm how their relationship changed later");

        assertEquals(TaskExecuteResult.Status.COMPLETED, result.status());
        assertEquals(List.of("method.searchLongTermMemory#01"), result.visibleEvidenceIds());
        verify(memorySearchTools).searchLongTermMemory("follow up on relationship progress");
    }

    @Test
    void shouldRemoveCrossedOutEvidenceFromCheckerContextAndFinalResult() {
        MemorySearchTools memorySearchTools = mock(MemorySearchTools.class);
        when(memorySearchTools.searchLongTermMemory("childhood trip"))
                .thenReturn(searchResult("We went to the beach together as kids."));
        when(memorySearchTools.searchLongTermMemory("relationship update"))
                .thenReturn(searchResult("They later resumed contact."));

        TaskSupervisorAiServiceFactory supervisorFactory = toolbox -> context -> {
            toolbox.searchLongTermMemory("childhood trip");
            toolbox.searchLongTermMemory("relationship update");
            toolbox.removeBlackboardEvidence("method.searchLongTermMemory#01");
            TaskSupervisorFinalDecision decision = new TaskSupervisorFinalDecision();
            decision.setCompletionClaim("COMPLETED");
            decision.setCandidateEvidenceIds(List.of("method.searchLongTermMemory#02"));
            decision.setOpenRisks(List.of());
            return decision;
        };

        AtomicReference<String> checkerContext = new AtomicReference<>();
        TaskCheckerAiService checker = context -> {
            checkerContext.set(context);
            TaskCheckerVerdict verdict = new TaskCheckerVerdict();
            verdict.setDecision("APPROVED");
            verdict.setReason("Evidence is sufficient.");
            return verdict;
        };

        ChatLogRepository chatLogRepository = mock(ChatLogRepository.class);
        TaskExecuteResult result = newAgent(supervisorFactory, checker, memorySearchTools, chatLogRepository)
                .handle("Confirm the later relationship update");

        assertEquals(TaskExecuteResult.Status.COMPLETED, result.status());
        assertEquals(List.of("method.searchLongTermMemory#02"), result.visibleEvidenceIds());
        assertTrue(result.responseText().contains("method.searchLongTermMemory#02"));
        assertFalse(result.responseText().contains("method.searchLongTermMemory#01"));
        assertTrue(checkerContext.get().contains("method.searchLongTermMemory#02"));
        assertFalse(checkerContext.get().contains("method.searchLongTermMemory#01"));
    }

    @Test
    void shouldKeepSupervisorAndCheckerContextsCompact() {
        MemorySearchTools memorySearchTools = mock(MemorySearchTools.class);
        when(memorySearchTools.searchLongTermMemory("childhood trip"))
                .thenReturn(searchResult("We went to the beach together as kids."));

        AtomicReference<String> supervisorContext = new AtomicReference<>();
        TaskSupervisorAiServiceFactory supervisorFactory = toolbox -> context -> {
            supervisorContext.set(context);
            toolbox.searchLongTermMemory("childhood trip");
            TaskSupervisorFinalDecision decision = new TaskSupervisorFinalDecision();
            decision.setCompletionClaim("COMPLETED");
            decision.setCandidateEvidenceIds(List.of("method.searchLongTermMemory#01"));
            decision.setOpenRisks(List.of());
            return decision;
        };

        AtomicReference<String> checkerContext = new AtomicReference<>();
        TaskCheckerAiService checker = context -> {
            checkerContext.set(context);
            return approvedVerdict();
        };

        ChatLogRepository chatLogRepository = mock(ChatLogRepository.class);
        TaskExecuteResult result = newAgent(supervisorFactory, checker, memorySearchTools, chatLogRepository)
                .handle("Please verify the childhood trip detail");

        assertEquals(TaskExecuteResult.Status.COMPLETED, result.status());
        assertFalse(supervisorContext.get().contains("taskRunId="));
        assertFalse(supervisorContext.get().contains("toolRoundsUsed="));
        assertFalse(supervisorContext.get().contains("checkerAttempt="));
        assertTrue(checkerContext.get().contains("<candidate_evidence>"));
        assertTrue(checkerContext.get().contains("<blackboard_evidence_index>"));
        assertTrue(checkerContext.get().contains("status=OK"));
        assertFalse(checkerContext.get().contains("createdAt="));
        assertFalse(checkerContext.get().contains("\"bundles\""));
        assertFalse(checkerContext.get().contains("<blackboard_readonly>"));
    }

    @Test
    void shouldReuseExistingEvidenceForDuplicateToolResults() {
        MemorySearchTools memorySearchTools = mock(MemorySearchTools.class);
        when(memorySearchTools.searchLongTermMemory("childhood trip"))
                .thenReturn(searchResult("We went to the beach together as kids."));

        TaskSupervisorAiServiceFactory supervisorFactory = toolbox -> context -> {
            toolbox.searchLongTermMemory("childhood trip");
            toolbox.searchLongTermMemory("childhood trip");
            TaskSupervisorFinalDecision decision = new TaskSupervisorFinalDecision();
            decision.setCompletionClaim("COMPLETED");
            decision.setCandidateEvidenceIds(List.of("method.searchLongTermMemory#01"));
            decision.setOpenRisks(List.of());
            return decision;
        };

        AtomicReference<String> checkerContext = new AtomicReference<>();
        TaskCheckerAiService checker = context -> {
            checkerContext.set(context);
            return approvedVerdict();
        };

        ChatLogRepository chatLogRepository = mock(ChatLogRepository.class);
        TaskExecuteResult result = newAgent(supervisorFactory, checker, memorySearchTools, chatLogRepository)
                .handle("Please verify the childhood trip detail");

        assertEquals(TaskExecuteResult.Status.COMPLETED, result.status());
        assertEquals(List.of("method.searchLongTermMemory#01"), result.visibleEvidenceIds());
        assertTrue(checkerContext.get().contains("reusedExistingEvidenceCount=1"));
        assertFalse(checkerContext.get().contains("method.searchLongTermMemory#02"));
        assertFalse(result.responseText().contains("method.searchLongTermMemory#02"));
        verify(memorySearchTools, times(2)).searchLongTermMemory("childhood trip");
    }

    @Test
    void shouldReturnCheckerFailureWhenCheckerThrows() {
        MemorySearchTools memorySearchTools = mock(MemorySearchTools.class);

        TaskSupervisorAiServiceFactory supervisorFactory = toolbox -> context -> {
            TaskSupervisorFinalDecision decision = new TaskSupervisorFinalDecision();
            decision.setCompletionClaim("COMPLETED");
            decision.setCandidateEvidenceIds(List.of());
            decision.setOpenRisks(List.of());
            return decision;
        };

        TaskCheckerAiService checker = context -> {
            throw new IllegalStateException("boom");
        };

        ChatLogRepository chatLogRepository = mock(ChatLogRepository.class);
        TaskExecuteResult result = newAgent(supervisorFactory, checker, memorySearchTools, chatLogRepository)
                .handle("Please verify the childhood trip detail");

        assertEquals(TaskExecuteResult.Status.FAILED, result.status());
        assertEquals("checker_invocation_failed", result.reasonCode());
    }

    @Test
    void shouldNormalizeLegacyRetrySupervisorDecision() {
        TaskCheckerAiService checker = context -> {
            TaskCheckerVerdict verdict = new TaskCheckerVerdict();
            verdict.setDecision("RETRY");
            verdict.setRetryInstruction("Look up one more clue.");
            verdict.setReason("Need one more step.");
            return verdict;
        };

        TaskSupervisorFinalDecision decision = new TaskSupervisorFinalDecision();
        decision.setCompletionClaim("COMPLETED");
        decision.setCandidateEvidenceIds(List.of());
        decision.setOpenRisks(List.of("Missing a follow-up."));

        TaskCheckerVerdict verdict = new TaskCheckerAgent(checker).check(
                new TaskBlackboard(),
                decision,
                "question",
                "request",
                "request"
        );

        assertEquals("RETRY", verdict.getDecision());
        assertEquals("Look up one more clue.", verdict.getRetryInstruction());
    }

    @Test
    void shouldSkipCheckerWhileSupervisorIsStillWorking() {
        MemorySearchTools memorySearchTools = mock(MemorySearchTools.class);
        when(memorySearchTools.searchLongTermMemory("childhood trip"))
                .thenReturn(searchResult("We went to the beach together as kids."));

        AtomicInteger supervisorCalls = new AtomicInteger();
        TaskSupervisorAiServiceFactory supervisorFactory = toolbox -> context -> {
            if (supervisorCalls.getAndIncrement() == 0) {
                toolbox.searchLongTermMemory("childhood trip");
                TaskSupervisorFinalDecision decision = new TaskSupervisorFinalDecision();
                decision.setCompletionClaim("WORKING");
                decision.setCandidateEvidenceIds(List.of("method.searchLongTermMemory#01"));
                decision.setOpenRisks(List.of());
                return decision;
            }

            TaskSupervisorFinalDecision decision = new TaskSupervisorFinalDecision();
            decision.setCompletionClaim("COMPLETED");
            decision.setCandidateEvidenceIds(List.of("method.searchLongTermMemory#01"));
            decision.setOpenRisks(List.of());
            return decision;
        };

        AtomicInteger checkerCalls = new AtomicInteger();
        TaskCheckerAiService checker = context -> {
            checkerCalls.incrementAndGet();
            return approvedVerdict();
        };

        ChatLogRepository chatLogRepository = mock(ChatLogRepository.class);
        TaskExecuteResult result = newAgent(supervisorFactory, checker, memorySearchTools, chatLogRepository)
                .handle("Please verify the childhood trip detail");

        assertEquals(TaskExecuteResult.Status.COMPLETED, result.status());
        assertEquals(2, supervisorCalls.get());
        assertEquals(1, checkerCalls.get());
    }

    @Test
    void shouldFailWhenSupervisorReturnsWorkingWithoutProgress() {
        MemorySearchTools memorySearchTools = mock(MemorySearchTools.class);

        TaskSupervisorAiServiceFactory supervisorFactory = toolbox -> context -> {
            TaskSupervisorFinalDecision decision = new TaskSupervisorFinalDecision();
            decision.setCompletionClaim("WORKING");
            decision.setCandidateEvidenceIds(List.of());
            decision.setOpenRisks(List.of());
            return decision;
        };

        TaskCheckerAiService checker = mock(TaskCheckerAiService.class);
        ChatLogRepository chatLogRepository = mock(ChatLogRepository.class);

        TaskExecuteResult result = newAgent(supervisorFactory, checker, memorySearchTools, chatLogRepository)
                .handle("Keep working");

        assertEquals(TaskExecuteResult.Status.FAILED, result.status());
        assertEquals("supervisor_working_without_progress", result.reasonCode());
        verifyNoInteractions(checker);
    }

    private TaskSupervisorAgent newAgent(TaskSupervisorAiServiceFactory supervisorFactory,
                                         TaskCheckerAiService checker,
                                         MemorySearchTools memorySearchTools,
                                         ChatLogRepository chatLogRepository) {
        return new TaskSupervisorAgent(
                supervisorFactory,
                new TaskCheckerAgent(checker),
                memorySearchTools,
                chatLogRepository,
                objectMapper
        );
    }

    private TaskCheckerVerdict approvedVerdict() {
        TaskCheckerVerdict verdict = new TaskCheckerVerdict();
        verdict.setDecision("APPROVED");
        verdict.setReason("Evidence is sufficient.");
        return verdict;
    }

    private MemorySearchTools.MemorySearchResult searchResult(String narrative) {
        MemorySearchTools.ArchiveNodeView archiveNodeView = new MemorySearchTools.ArchiveNodeView(
                1L,
                "group-1",
                0,
                "topic",
                "",
                narrative
        );
        MemorySearchTools.MemorySearchBundle bundle = new MemorySearchTools.MemorySearchBundle(
                1L,
                "group-1",
                0.91,
                List.of(archiveNodeView),
                List.of(),
                false
        );
        return new MemorySearchTools.MemorySearchResult(
                "query",
                "OK",
                "Relevant long-term memory found.",
                List.of(bundle),
                false
        );
    }

    private ChatLogEntity userLog(String text) {
        ChatLogEntity entity = new ChatLogEntity();
        entity.setRole("USER");
        entity.setContent(ChatMessageSerializer.messageToJson(UserMessage.from(text)));
        return entity;
    }
}
