package p1.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import p1.component.agent.memory.MemoryWriteService;
import p1.component.agent.memory.model.ExtractedMemoryEvent;
import p1.infrastructure.vector.ArchiveVectorLibrary;
import p1.model.document.MemoryArchiveDocument;
import p1.model.document.RecentEventGroupDocument;
import p1.service.archive.ArchiveEmbeddingService;
import p1.service.archive.graph.ArchiveGraphService;
import p1.service.archive.graph.ArchiveLinkService;
import p1.service.markdown.MemoryArchiveStore;
import p1.service.markdown.RecentEventGroupMarkdownService;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemoryWriteServiceTest {

    @Test
    void shouldWriteEventGroupToArchiveAndRecent24h() {
        MemoryArchiveStore archiveService = mock(MemoryArchiveStore.class);
        RecentEventGroupMarkdownService recentEventGroupService = mock(RecentEventGroupMarkdownService.class);
        ArchiveLinkService archiveLinkService = mock(ArchiveLinkService.class);
        ArchiveEmbeddingService archiveEmbeddingService = mock(ArchiveEmbeddingService.class);
        ArchiveGraphService archiveGraphService = mock(ArchiveGraphService.class);

        when(recentEventGroupService.allocateGroupId()).thenReturn("group-1");
        when(recentEventGroupService.create(
                eq("group-1"),
                eq("test"),
                anyList(),
                eq(List.of("recent-1", "recent-2")),
                eq(List.of("group-tag-a", "group-tag-b"))
        )).thenAnswer(invocation -> {
            RecentEventGroupDocument group = new RecentEventGroupDocument();
            group.setId("group-1");
            group.setSessionId("test");
            return group;
        });

        AtomicLong idSequence = new AtomicLong(100);
        when(archiveService.save(any())).thenAnswer(invocation -> {
            MemoryArchiveDocument archive = invocation.getArgument(0);
            archive.setId(idSequence.incrementAndGet());
            return archive;
        });
        when(archiveEmbeddingService.indexArchives(anyString(), eq(ArchiveVectorLibrary.RECENT_24H), anyList(), eq("group-1")))
                .thenReturn(List.of("recent-1", "recent-2"));
        when(archiveEmbeddingService.indexArchives(anyString(), eq(ArchiveVectorLibrary.ARCHIVE), anyList(), any()))
                .thenReturn(List.of("archive-doc"));

        MemoryWriteService service = new MemoryWriteService(
                archiveService,
                recentEventGroupService,
                archiveLinkService,
                archiveEmbeddingService,
                archiveGraphService
        );

        ExtractedMemoryEvent first = new ExtractedMemoryEvent();
        first.setTopic("relationship-progress");
        first.setKeywordSummary("The user expressed stronger emotional dependence.");
        first.setNarrative("The user clearly described wanting the relationship to become closer.");
        first.setImportanceScore(8);

        ExtractedMemoryEvent second = new ExtractedMemoryEvent();
        second.setTopic("long-term-companionship");
        second.setKeywordSummary("The user confirmed a long-term companionship expectation.");
        second.setNarrative("The dialogue moved further toward long-term companionship and stable support.");
        second.setImportanceScore(7);

        List<MemoryArchiveDocument> saved = service.saveEventGroup("test", List.of(first, second), List.of("group-tag-a", "group-tag-b"));

        ArgumentCaptor<MemoryArchiveDocument> archiveCaptor = ArgumentCaptor.forClass(MemoryArchiveDocument.class);
        verify(archiveService, times(2)).save(archiveCaptor.capture());
        verify(archiveEmbeddingService, times(2)).indexArchives(eq("test"), eq(ArchiveVectorLibrary.ARCHIVE), anyList(), eq("group-1"));
        verify(archiveEmbeddingService).indexArchives(eq("test"), eq(ArchiveVectorLibrary.RECENT_24H), anyList(), eq("group-1"));
        verify(archiveGraphService).enrichGroupGraph(eq("test"), anyList(), eq(List.of("group-tag-a", "group-tag-b")));
        verify(recentEventGroupService).create(eq("group-1"), eq("test"), anyList(), eq(List.of("recent-1", "recent-2")), eq(List.of("group-tag-a", "group-tag-b")));
        verify(archiveGraphService).syncGroupLinks(eq("test"), eq("group-1"), anyList());

        List<MemoryArchiveDocument> captured = archiveCaptor.getAllValues();
        assertEquals(2, captured.size());
        assertEquals("group-1", captured.get(0).getGroupId());
        assertEquals(0, captured.get(0).getGroupOrder());
        assertEquals(List.of("group-tag-a", "group-tag-b"), captured.get(0).getGroupTags());
        assertEquals("relationship-progress", captured.get(0).getTopic());
        assertEquals("group-1", captured.get(1).getGroupId());
        assertEquals(1, captured.get(1).getGroupOrder());
        assertEquals(List.of("group-tag-a", "group-tag-b"), captured.get(1).getGroupTags());
        assertEquals("long-term-companionship", captured.get(1).getTopic());
        assertTrue(saved.stream().allMatch(archive -> "test".equals(archive.getSessionId())));
        assertEquals(List.of(101L, 102L), saved.stream().map(MemoryArchiveDocument::getId).toList());
    }
}
