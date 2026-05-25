package p1.service.markdown;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import p1.component.agent.memory.model.RecentEventGroupLinkRecord;
import p1.config.prop.AssistantProperties;
import p1.infrastructure.markdown.MarkdownFileAccess;
import p1.infrastructure.markdown.MarkdownMemoryArchiveStore;
import p1.infrastructure.markdown.MarkdownRecentEventGroupStore;
import p1.infrastructure.markdown.assembler.MemoryArchiveMdAssembler;
import p1.infrastructure.markdown.assembler.RecentEventGroupMdAssembler;
import p1.infrastructure.markdown.io.MarkdownFrontmatterIO;
import p1.infrastructure.markdown.model.MarkdownDocument;
import p1.model.document.MemoryArchiveDocument;
import p1.model.document.RecentEventGroupDocument;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecentEventGroupMarkdownServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCreateTouchAndDeleteRecentEventGroup() throws Exception {
        AssistantProperties props = new AssistantProperties();
        AssistantProperties.MdRepositoryConfig repositoryConfig = new AssistantProperties.MdRepositoryConfig();
        repositoryConfig.setPath(tempDir.toString());
        props.setMdRepository(repositoryConfig);
        MarkdownFrontmatterIO markdownFrontmatterIO = new MarkdownFrontmatterIO();
        MarkdownFileAccess fileAccess = new MarkdownFileAccess(markdownFrontmatterIO);
        MemoryArchiveStore archiveService = new MarkdownMemoryArchiveStore(
                props,
                fileAccess,
                new MemoryArchiveMdAssembler()
        );
        RecentEventGroupMarkdownService service = new RecentEventGroupMarkdownService(
                new MarkdownRecentEventGroupStore(
                        props,
                        fileAccess,
                        new RecentEventGroupMdAssembler(),
                        archiveService
                )
        );

        MemoryArchiveDocument first = new MemoryArchiveDocument();
        first.setSessionId("test");
        first.setTopic("relationship-progress");
        first.setKeywordSummary("relationship-progress-summary");
        first.setNarrative("relationship-progress-detail");
        first = archiveService.save(first);
        MemoryArchiveDocument second = new MemoryArchiveDocument();
        second.setSessionId("test");
        second.setTopic("long-term-companionship");
        second.setKeywordSummary("long-term-companionship-summary");
        second.setNarrative("long-term-companionship-detail");
        second = archiveService.save(second);

        RecentEventGroupDocument created = service.create(
                "group-1",
                "test",
                List.of(first, second),
                List.of("recent-1", "recent-2"),
                List.of("night-market", "jiang-nan")
        );

        RecentEventGroupDocument loaded = service.findById("test", "group-1").orElseThrow();
        assertEquals(created.getId(), loaded.getId());
        assertEquals("relationship-progress", loaded.getTopic());
        assertEquals(first.getId(), loaded.getHeadArchiveId());
        assertEquals(second.getId(), loaded.getTailArchiveId());
        assertEquals(List.of(first.getId(), second.getId()), loaded.getArchiveIds());
        assertEquals(List.of("recent-1", "recent-2"), loaded.getRecentVectorDocumentIds());
        assertEquals(List.of("night-market", "jiang-nan"), loaded.getGroupTags());
        MarkdownDocument groupMarkdown = markdownFrontmatterIO.read(tempDir.resolve("_system/sessions/test/recent-event-groups/group-1.md"));
        assertTrue(groupMarkdown.body().contains("## Events"));
        assertTrue(groupMarkdown.body().contains("[[sessions/test/wiki/memories/relationship-progress|relationship-progress]]"));
        assertTrue(groupMarkdown.body().contains("[[sessions/test/wiki/memories/long-term-companionship|long-term-companionship]]"));
        assertTrue(groupMarkdown.body().indexOf("relationship-progress") < groupMarkdown.body().indexOf("long-term-companionship"));

        LocalDateTime hitTime = LocalDateTime.now().plusHours(1);
        service.touch("test", "group-1", hitTime);
        RecentEventGroupDocument touched = service.findById("test", "group-1").orElseThrow();
        assertEquals(hitTime, touched.getLastHitAt());
        assertEquals(1, service.findAllBySessionId("test").size());

        service.delete("test", "group-1");
        assertTrue(service.findById("test", "group-1").isEmpty());
        assertFalse(tempDir.resolve("_system/sessions/test/recent-event-groups/group-1.md").toFile().exists());
    }

    @Test
    void shouldPersistBidirectionalGroupLinks() throws Exception {
        AssistantProperties props = new AssistantProperties();
        AssistantProperties.MdRepositoryConfig repositoryConfig = new AssistantProperties.MdRepositoryConfig();
        repositoryConfig.setPath(tempDir.toString());
        props.setMdRepository(repositoryConfig);
        MarkdownFrontmatterIO markdownFrontmatterIO = new MarkdownFrontmatterIO();
        MarkdownFileAccess fileAccess = new MarkdownFileAccess(markdownFrontmatterIO);
        MemoryArchiveStore archiveService = new MarkdownMemoryArchiveStore(
                props,
                fileAccess,
                new MemoryArchiveMdAssembler()
        );
        RecentEventGroupMarkdownService service = new RecentEventGroupMarkdownService(
                new MarkdownRecentEventGroupStore(
                        props,
                        fileAccess,
                        new RecentEventGroupMdAssembler(),
                        archiveService
                )
        );

        MemoryArchiveDocument first = new MemoryArchiveDocument();
        first.setSessionId("test");
        first.setTopic("current-group-head");
        first = archiveService.save(first);

        MemoryArchiveDocument second = new MemoryArchiveDocument();
        second.setSessionId("test");
        second.setTopic("matched-group-head");
        second = archiveService.save(second);

        service.create("group-current", "test", List.of(first), List.of("recent-1"), List.of("market"));
        service.create("group-matched", "test", List.of(second), List.of("recent-2"), List.of("mirror"));

        service.addGroupLink(
                "test",
                "group-current",
                "group-matched",
                "recent_window_to",
                "recent_window_from",
                0.87,
                "recent winner"
        );

        RecentEventGroupDocument current = service.findById("test", "group-current").orElseThrow();
        RecentEventGroupDocument matched = service.findById("test", "group-matched").orElseThrow();

        assertEquals(1, current.getLinks().size());
        RecentEventGroupLinkRecord currentLink = current.getLinks().getFirst();
        assertEquals("recent_window_to", currentLink.getRelation());
        assertEquals("group-matched", currentLink.getTargetGroupId());
        assertEquals("matched-group-head", currentLink.getTargetTopic());
        assertEquals(0.87, currentLink.getConfidence());
        assertEquals("recent winner", currentLink.getReason());

        assertEquals(1, matched.getLinks().size());
        RecentEventGroupLinkRecord matchedLink = matched.getLinks().getFirst();
        assertEquals("recent_window_from", matchedLink.getRelation());
        assertEquals("group-current", matchedLink.getTargetGroupId());
        assertEquals("current-group-head", matchedLink.getTargetTopic());

        MarkdownDocument currentMarkdown = markdownFrontmatterIO.read(
                tempDir.resolve("_system/sessions/test/recent-event-groups/group-current.md")
        );
        assertTrue(String.valueOf(currentMarkdown.frontmatter().get("links")).contains("target_group_id=group-matched"));
        assertTrue(String.valueOf(currentMarkdown.frontmatter().get("links")).contains("target_topic=matched-group-head"));
    }
}
