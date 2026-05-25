package p1.service.markdown;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import p1.component.agent.memory.model.ArchiveLink;
import p1.config.prop.AssistantProperties;
import p1.infrastructure.markdown.MarkdownFileAccess;
import p1.infrastructure.markdown.MarkdownMemoryArchiveStore;
import p1.infrastructure.markdown.assembler.MemoryArchiveMdAssembler;
import p1.infrastructure.markdown.io.MarkdownFrontmatterIO;
import p1.infrastructure.markdown.model.MarkdownDocument;
import p1.model.document.MemoryArchiveDocument;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownMemoryArchiveStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldStoreAllLinksInMemoryYamlAndRenderOnlyNonTimelineLinksInBody() throws Exception {
        AssistantProperties props = props();
        MarkdownFrontmatterIO markdownFrontmatterIO = new MarkdownFrontmatterIO();
        MemoryArchiveMdAssembler assembler = new MemoryArchiveMdAssembler();
        MemoryArchiveStore service = new MarkdownMemoryArchiveStore(props, new MarkdownFileAccess(markdownFrontmatterIO), assembler);

        MemoryArchiveDocument timelineTarget = archive("test", "timeline-target");
        service.save(timelineTarget);

        MemoryArchiveDocument logicTarget = archive("test", "logic-target");
        service.save(logicTarget);

        MemoryArchiveDocument source = archive("test", "source-topic");
        source.setLinks(List.of(
                new ArchiveLink("next_in_time", timelineTarget.getId(), timelineTarget.getTopic(), 1.0, "time edge"),
                new ArchiveLink("recent_window_to", logicTarget.getId(), logicTarget.getTopic(), 0.81, "logic edge")
        ));
        MemoryArchiveDocument saved = service.save(source);

        Path sourcePath = tempDir.resolve("sessions/test/wiki/memories/source-topic.md");

        assertTrue(Files.exists(sourcePath));

        MarkdownDocument sourceDoc = markdownFrontmatterIO.read(sourcePath);

        assertIterableEquals(List.of("next_in_time", "recent_window_to"), relationsOf(sourceDoc));
        assertFalse(sourceDoc.body().contains("[[sessions/test/wiki/memories/timeline-target|timeline-target]]"));
        assertFalse(sourceDoc.body().contains("next_in_time"));
        assertTrue(sourceDoc.body().contains("[[sessions/test/wiki/memories/logic-target|logic-target]]"));
        assertTrue(sourceDoc.body().contains("recent_window_to"));

        MemoryArchiveDocument loaded = service.findById(saved.getId()).orElseThrow();
        assertEquals(2, loaded.getLinks().size());
        assertTrue(loaded.getLinks().stream().anyMatch(link -> "next_in_time".equals(link.getRelation())));
        assertTrue(loaded.getLinks().stream().anyMatch(link -> "timeline-target".equals(link.getTargetTopic())));
        assertTrue(loaded.getLinks().stream().anyMatch(link -> "recent_window_to".equals(link.getRelation())));
        assertTrue(loaded.getLinks().stream().anyMatch(link -> "logic-target".equals(link.getTargetTopic())));
    }

    @Test
    void shouldLoadExistingMemoryFileAndPreserveMemoryPathOnSave() throws Exception {
        AssistantProperties props = props();
        MarkdownFrontmatterIO markdownFrontmatterIO = new MarkdownFrontmatterIO();
        MemoryArchiveMdAssembler assembler = new MemoryArchiveMdAssembler();
        MemoryArchiveStore service = new MarkdownMemoryArchiveStore(props, new MarkdownFileAccess(markdownFrontmatterIO), assembler);

        MemoryArchiveDocument existing = archive("test", "existing-topic");
        existing.setId(7L);
        existing.setCreatedAt(LocalDateTime.parse("2026-04-20T10:00:00"));
        existing.setUpdatedAt(LocalDateTime.parse("2026-04-20T10:05:00"));
        existing.setGroupId("group-7");
        existing.setGroupTags(List.of("night-market", "jiang-nan"));
        existing.setLinks(List.of(
                new ArchiveLink("next_in_time", 9L, "topic-9", 1.0, "time edge"),
                new ArchiveLink("related_to", 10L, "topic-10", 0.77, "logic edge")
        ));

        Path existingPath = tempDir.resolve("sessions/test/wiki/memories/existing-topic.md");
        markdownFrontmatterIO.write(existingPath, assembler.toMarkdown(existing));

        MemoryArchiveDocument loaded = service.findById(7L).orElseThrow();
        assertEquals(2, loaded.getLinks().size());
        assertEquals("group-7", loaded.getGroupId());
        assertEquals(List.of("night-market", "jiang-nan"), loaded.getGroupTags());

        service.save(loaded);

        assertTrue(Files.exists(existingPath));
        MarkdownDocument existingDoc = markdownFrontmatterIO.read(existingPath);
        assertIterableEquals(
                List.of(
                        "group-7",
                        "night-market",
                        "jiang-nan"
                ),
                stringList(existingDoc.frontmatter().get("tags"))
        );
        assertIterableEquals(List.of("night-market", "jiang-nan"), stringList(existingDoc.frontmatter().get("group_tags")));
        assertIterableEquals(List.of("next_in_time", "related_to"), relationsOf(existingDoc));
        assertIterableEquals(List.of("topic-9", "topic-10"), targetTopicsOf(existingDoc));
        assertFalse(existingDoc.body().contains("next_in_time"));
        assertTrue(existingDoc.body().contains("related_to"));
    }

    private AssistantProperties props() {
        AssistantProperties props = new AssistantProperties();
        AssistantProperties.MdRepositoryConfig repositoryConfig = new AssistantProperties.MdRepositoryConfig();
        repositoryConfig.setPath(tempDir.toString());
        props.setMdRepository(repositoryConfig);
        return props;
    }

    private MemoryArchiveDocument archive(String sessionId, String topic) {
        MemoryArchiveDocument archive = new MemoryArchiveDocument();
        archive.setSessionId(sessionId);
        archive.setTopic(topic);
        archive.setKeywordSummary(topic + "-summary");
        archive.setNarrative(topic + "-detail");
        return archive;
    }

    @SuppressWarnings("unchecked")
    private List<String> relationsOf(MarkdownDocument document) {
        Object rawLinks = document.frontmatter().get("links");
        if (!(rawLinks instanceof List<?> links)) {
            return List.of();
        }

        return links.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(link -> String.valueOf(link.get("relation")))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> targetTopicsOf(MarkdownDocument document) {
        Object rawLinks = document.frontmatter().get("links");
        if (!(rawLinks instanceof List<?> links)) {
            return List.of();
        }

        return links.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(link -> String.valueOf(link.get("target_topic")))
                .toList();
    }

    private List<String> stringList(Object rawValue) {
        if (!(rawValue instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }
}
