package p1.service.markdown;

import org.junit.jupiter.api.Test;
import p1.component.agent.memory.model.ArchiveLink;
import p1.infrastructure.markdown.assembler.MemoryArchiveMdAssembler;
import p1.infrastructure.markdown.model.MarkdownDocument;
import p1.model.document.MemoryArchiveDocument;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryArchiveMdAssemblerTest {

    private final MemoryArchiveMdAssembler mapper = new MemoryArchiveMdAssembler();

    @Test
    void shouldKeepTimelineLinksOnlyInMetadataAndRenderLogicLinksIntoBody() {
        MemoryArchiveDocument archive = new MemoryArchiveDocument();
        archive.setId(12L);
        archive.setSessionId("test");
        archive.setGroupId("group-20260424210000");
        archive.setGroupTags(List.of("night-market", "jiang-nan"));
        archive.setTopic("relationship-progress");
        archive.setKeywordSummary("The user expressed stronger emotional dependence.");
        archive.setNarrative("The user showed a more stable and explicit emotional dependence in the night-market dialogue.");
        archive.setEventGraph("[[sessions/test/wiki/memories/earlier-scene|earlier-scene]]\n[[sessions/test/wiki/memories/later-scene|later-scene]]");
        archive.setEventGraphTrace("- winner: group-old#13\n\n### Query Matches\n\n- query#1 archiveId=12 weight=1.000 text=`relationship-progress` matchCount=1");
        archive.setCreatedAt(LocalDateTime.parse("2026-04-18T21:00:00"));
        archive.setUpdatedAt(LocalDateTime.parse("2026-04-18T21:05:00"));
        archive.setLinks(List.of(
                new ArchiveLink("next_in_time", 13L, "shared-experience", 1.0, "next event in the same batch"),
                new ArchiveLink("related_to", 14L, "emotional-anchor", 0.86, "same topic cluster")
        ));

        MarkdownDocument markdown = mapper.toMarkdown(
                archive,
                List.of(
                        new MemoryArchiveMdAssembler.RenderedArchiveLink(
                                "next_in_time",
                                "[[sessions/test/wiki/memories/shared-experience|shared-experience]]",
                                "next event in the same batch"
                        ),
                        new MemoryArchiveMdAssembler.RenderedArchiveLink(
                                "related_to",
                                "[[sessions/test/wiki/memories/emotional-anchor|emotional-anchor]]",
                                "same topic cluster"
                        )
                )
        );

        assertFalse(markdown.frontmatter().containsKey("title"));
        assertEquals(
                List.of(
                        "group-20260424210000",
                        "night-market"
                ),
                markdown.frontmatter().get("tags")
        );
        assertEquals(List.of("night-market", "jiang-nan"), markdown.frontmatter().get("group_tags"));
        assertTrue(markdown.body().contains("# relationship-progress"));
        assertFalse(markdown.body().contains("## Event Graph\n\n"));
        assertFalse(markdown.body().contains("[[sessions/test/wiki/memories/earlier-scene|earlier-scene]]"));
        assertTrue(markdown.body().contains("## Links"));
        assertFalse(markdown.body().contains("next_in_time"));
        assertFalse(markdown.body().contains("[[sessions/test/wiki/memories/shared-experience|shared-experience]]"));
        assertTrue(markdown.body().contains("[[sessions/test/wiki/memories/emotional-anchor|emotional-anchor]]"));
        assertTrue(markdown.body().contains("## Event Graph Trace"));

        MemoryArchiveDocument restored = mapper.fromMarkdown(markdown);
        assertEquals("The user showed a more stable and explicit emotional dependence in the night-market dialogue.", restored.getNarrative());
        assertEquals("The user expressed stronger emotional dependence.", restored.getKeywordSummary());
        assertEquals("group-20260424210000", restored.getGroupId());
        assertEquals(List.of("night-market", "jiang-nan"), restored.getGroupTags());
        assertEquals("", restored.getEventGraph());
        assertEquals(archive.getEventGraphTrace(), restored.getEventGraphTrace());
        assertEquals(2, restored.getLinks().size());
        assertEquals("next_in_time", restored.getLinks().getFirst().getRelation());
        assertEquals(13L, restored.getLinks().getFirst().getTargetArchiveId());
        assertEquals("shared-experience", restored.getLinks().getFirst().getTargetTopic());
        assertEquals("related_to", restored.getLinks().get(1).getRelation());
        assertEquals(14L, restored.getLinks().get(1).getTargetArchiveId());
        assertEquals("emotional-anchor", restored.getLinks().get(1).getTargetTopic());
    }
}
