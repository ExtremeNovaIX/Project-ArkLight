package p1.component.agent.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import p1.component.agent.memory.model.ExtractedMemoryEvent;
import p1.component.agent.memory.model.FactExtractionPipelineResult;
import p1.component.agent.rp.context.SummaryCacheManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MemoryAsyncCompressorTest {

    @Test
    void shouldPersistPipelineResultAndUpdateSummary() {
        SummaryCacheManager summaryCacheManager = mock(SummaryCacheManager.class);
        MemoryCompressionPipeline memoryCompressionPipeline = mock(MemoryCompressionPipeline.class);
        MemoryWriteService memoryWriteService = mock(MemoryWriteService.class);

        MemoryAsyncCompressor compressor = new MemoryAsyncCompressor(
                summaryCacheManager,
                memoryCompressionPipeline,
                memoryWriteService
        );

        ExtractedMemoryEvent mergedEvent = new ExtractedMemoryEvent();
        mergedEvent.setTopic("relationship-progress");
        mergedEvent.setNarrative("The user described stronger emotional dependence.");
        mergedEvent.setKeywordSummary("The user expressed stronger emotional dependence.");
        mergedEvent.setImportanceScore(8);

        FactExtractionPipelineResult pipelineResult = new FactExtractionPipelineResult(
                List.of(mergedEvent),
                List.of("dependency-shift", "relationship"),
                "This batch centers on relationship progress."
        );

        List<ChatMessage> messages = List.of(
                UserMessage.from("I have become more dependent on you lately."),
                AiMessage.from("I will remember that change.")
        );
        when(memoryCompressionPipeline.buildPipelineResult("test", messages))
                .thenReturn(java.util.Optional.of(pipelineResult));

        AtomicInteger successCounter = new AtomicInteger();
        AtomicInteger failureCounter = new AtomicInteger();
        compressor.compressAsync("test", messages, successCounter::incrementAndGet, failureCounter::incrementAndGet);

        @SuppressWarnings("unchecked")
        var eventCaptor = forClass(List.class);
        @SuppressWarnings("unchecked")
        var tagCaptor = forClass(List.class);
        verify(memoryWriteService).saveEventGroup(eq("test"), eventCaptor.capture(), tagCaptor.capture());
        verify(summaryCacheManager).updateSummary("test", "This batch centers on relationship progress.");

        List<ExtractedMemoryEvent> savedEvents = eventCaptor.getValue();
        assertEquals(1, savedEvents.size());
        assertEquals("relationship-progress", savedEvents.getFirst().getTopic());
        assertEquals(List.of("dependency-shift", "relationship"), tagCaptor.getValue());
        assertEquals(1, successCounter.get());
        assertEquals(0, failureCounter.get());
    }
}
