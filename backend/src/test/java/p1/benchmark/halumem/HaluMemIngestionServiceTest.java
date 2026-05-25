package p1.benchmark.halumem;

import org.junit.jupiter.api.Test;
import p1.component.agent.memory.MemoryCompressionPipeline;
import p1.component.agent.memory.MemoryWriteService;
import p1.component.agent.memory.model.ExtractedMemoryEvent;
import p1.component.agent.memory.model.FactExtractionPipelineResult;
import p1.component.agent.rp.context.SummaryCacheManager;
import p1.model.document.MemoryArchiveDocument;
import p1.service.markdown.MemoryArchiveStore;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class HaluMemIngestionServiceTest {

    @Test
    void shouldRunRealMemoryPipelineInsteadOfRawTranscriptPassThrough() {
        MemoryCompressionPipeline pipeline = mock(MemoryCompressionPipeline.class);
        MemoryWriteService memoryWriteService = mock(MemoryWriteService.class);
        SummaryCacheManager summaryCacheManager = mock(SummaryCacheManager.class);
        MemoryArchiveStore archiveStore = mock(MemoryArchiveStore.class);
        HaluMemIngestionService service = new HaluMemIngestionService(
                pipeline,
                memoryWriteService,
                summaryCacheManager,
                archiveStore
        );

        ExtractedMemoryEvent event = new ExtractedMemoryEvent();
        event.setTopic("topic");
        event.setKeywordSummary("summary");
        event.setNarrative("narrative");
        when(pipeline.buildPipelineResult(eq("bench-session"), any(List.class)))
                .thenReturn(Optional.of(new FactExtractionPipelineResult(List.of(event), List.of("tag-a"), "sum")));
        when(memoryWriteService.saveEventGroup(eq("bench-session"), eq(List.of(event)), eq(List.of("tag-a")), eq(List.of("benchmark:halumem", "halumem_session:session-1", "dataset:halumem"))))
                .thenReturn(List.of(new MemoryArchiveDocument()));
        when(archiveStore.findAllOrderByIdAsc("bench-session")).thenReturn(List.of());

        HaluMemSessionIngestResponse response = service.ingest(new HaluMemSessionIngestRequest(
                "bench-session",
                "session-1",
                List.of("dataset:halumem"),
                List.of(
                        new HaluMemMessageDTO("user", "u1"),
                        new HaluMemMessageDTO("assistant", "a1")
                )
        ));

        verify(pipeline).buildPipelineResult(eq("bench-session"), any(List.class));
        verify(summaryCacheManager).updateSummary("bench-session", "sum");
        assertEquals(2, response.acceptedMessageCount());
        assertEquals(1, response.extractedMemoryCount());
        assertEquals(1, response.persistedArchiveCount());
        assertEquals(List.of("tag-a"), response.tags());
        assertEquals("sum", response.summary());
    }

    @Test
    void shouldSkipPersistenceWhenPipelineProducesNothing() {
        MemoryCompressionPipeline pipeline = mock(MemoryCompressionPipeline.class);
        MemoryWriteService memoryWriteService = mock(MemoryWriteService.class);
        SummaryCacheManager summaryCacheManager = mock(SummaryCacheManager.class);
        MemoryArchiveStore archiveStore = mock(MemoryArchiveStore.class);
        HaluMemIngestionService service = new HaluMemIngestionService(
                pipeline,
                memoryWriteService,
                summaryCacheManager,
                archiveStore
        );

        when(pipeline.buildPipelineResult(eq("bench-session"), any(List.class))).thenReturn(Optional.empty());
        when(archiveStore.findAllOrderByIdAsc("bench-session")).thenReturn(List.of());

        HaluMemSessionIngestResponse response = service.ingest(new HaluMemSessionIngestRequest(
                "bench-session",
                "",
                List.of(),
                List.of(new HaluMemMessageDTO("user", "u1"))
        ));

        assertEquals(0, response.extractedMemoryCount());
        assertEquals(0, response.persistedArchiveCount());
        assertEquals(List.of(), response.tags());
        assertEquals("", response.summary());
    }
}
