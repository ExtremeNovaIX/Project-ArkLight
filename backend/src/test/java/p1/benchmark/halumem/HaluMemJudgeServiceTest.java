package p1.benchmark.halumem;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class HaluMemJudgeServiceTest {

    @Test
    void shouldComputePrecisionRecallAndF1FromJudgeCounts() {
        HaluMemMemoryJudgeAiService memoryJudgeAiService = mock(HaluMemMemoryJudgeAiService.class);
        HaluMemQaJudgeAiService qaJudgeAiService = mock(HaluMemQaJudgeAiService.class);
        HaluMemJudgeService service = new HaluMemJudgeService(memoryJudgeAiService, qaJudgeAiService);

        when(memoryJudgeAiService.judge(2, 3, "1. a\n2. b", "1. x\n2. y\n3. z"))
                .thenReturn(new HaluMemMemoryJudgeAiVerdict("ok", 1, 2, List.of("b"), List.of("z")));

        HaluMemJudgeMemoryResponse response = service.judgeMemory(new HaluMemJudgeMemoryRequest(
                List.of("a", "b"),
                List.of("x", "y", "z")
        ));

        assertEquals(2, response.goldCount());
        assertEquals(3, response.systemCount());
        assertEquals(1, response.matchedGoldCount());
        assertEquals(2, response.supportedSystemCount());
        assertEquals(0.6667, response.precision());
        assertEquals(0.5, response.recall());
        assertEquals(0.5714, response.f1());
    }

    @Test
    void shouldFallbackToLexicalJudgeWhenAiJudgeFails() {
        HaluMemMemoryJudgeAiService memoryJudgeAiService = mock(HaluMemMemoryJudgeAiService.class);
        HaluMemQaJudgeAiService qaJudgeAiService = mock(HaluMemQaJudgeAiService.class);
        HaluMemJudgeService service = new HaluMemJudgeService(memoryJudgeAiService, qaJudgeAiService);

        doThrow(new RuntimeException("parse failed"))
                .when(memoryJudgeAiService)
                .judge(2, 2, "1. Martin likes green tea\n2. Martin dislikes horror films",
                        "1. topic=tea | summary=Martin likes green tea\n2. topic=movies | summary=Martin prefers documentaries");

        HaluMemJudgeMemoryResponse response = service.judgeMemory(new HaluMemJudgeMemoryRequest(
                List.of("Martin likes green tea", "Martin dislikes horror films"),
                List.of("topic=tea | summary=Martin likes green tea", "topic=movies | summary=Martin prefers documentaries")
        ));

        assertEquals(1, response.matchedGoldCount());
        assertEquals(1, response.supportedSystemCount());
        assertEquals(0.5, response.precision());
        assertEquals(0.5, response.recall());
        assertEquals(0.5, response.f1());
        assertTrue(response.reasoning().startsWith("Lexical fallback judge used"));
        assertEquals(1, response.missingGoldItems().size());
        assertEquals(1, response.unsupportedSystemItems().size());
    }

    @Test
    void shouldPassRetrievedContextToQaJudge() {
        HaluMemMemoryJudgeAiService memoryJudgeAiService = mock(HaluMemMemoryJudgeAiService.class);
        HaluMemQaJudgeAiService qaJudgeAiService = mock(HaluMemQaJudgeAiService.class);
        HaluMemJudgeService service = new HaluMemJudgeService(memoryJudgeAiService, qaJudgeAiService);

        when(qaJudgeAiService.judge(
                "q",
                "gold",
                "dataset context",
                "retrieved context",
                "answer"
        )).thenReturn(new HaluMemQaJudgeAiVerdict("ok", "CORRECT", 1.0, false));

        HaluMemJudgeQaResponse response = service.judgeQa(new HaluMemJudgeQaRequest(
                "q",
                "gold",
                "answer",
                "dataset context",
                "retrieved context"
        ));

        verify(qaJudgeAiService).judge("q", "gold", "dataset context", "retrieved context", "answer");
        assertEquals("CORRECT", response.verdict());
        assertEquals(1.0, response.score());
    }
}
