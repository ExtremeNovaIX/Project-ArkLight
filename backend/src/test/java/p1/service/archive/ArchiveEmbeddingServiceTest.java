package p1.service.archive;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import p1.config.prop.AssistantProperties;
import p1.infrastructure.markdown.MarkdownFileAccess;
import p1.infrastructure.markdown.MarkdownMemoryArchiveStore;
import p1.infrastructure.markdown.assembler.MemoryArchiveMdAssembler;
import p1.infrastructure.markdown.io.MarkdownFrontmatterIO;
import p1.infrastructure.vector.ArchiveVectorLibrary;
import p1.infrastructure.vector.MemoryVectorDocumentIds;
import p1.infrastructure.vector.SessionMemoryVectorStoreFactory;
import p1.model.document.MemoryArchiveDocument;
import p1.service.EmbeddingService;
import p1.service.markdown.MemoryArchiveStore;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ArchiveEmbeddingServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRoundTripRecent24hMetadataFromLuceneStore() {
        AssistantProperties props = new AssistantProperties();
        AssistantProperties.EmbeddingStoreConfig embeddingStoreConfig = new AssistantProperties.EmbeddingStoreConfig();
        embeddingStoreConfig.setPath(tempDir.resolve("vectors").toString());
        props.setEmbeddingStore(embeddingStoreConfig);

        AssistantProperties.MdRepositoryConfig mdRepositoryConfig = new AssistantProperties.MdRepositoryConfig();
        mdRepositoryConfig.setPath(tempDir.resolve("memory").toString());
        props.setMdRepository(mdRepositoryConfig);

        MemoryArchiveStore archiveService = new MarkdownMemoryArchiveStore(
                props,
                new MarkdownFileAccess(new MarkdownFrontmatterIO()),
                new MemoryArchiveMdAssembler()
        );
        EmbeddingModel embeddingModel = new TestEmbeddingModel();
        EmbeddingService embeddingService = new EmbeddingService(
                new SessionMemoryVectorStoreFactory(props),
                embeddingModel
        );
        ArchiveEmbeddingService service = new ArchiveEmbeddingService(embeddingService, archiveService);

        MemoryArchiveDocument first = archive("test", "group-1", "recent hit one");
        MemoryArchiveDocument second = archive("test", "group-1", "recent hit two");
        first = archiveService.save(first);
        second = archiveService.save(second);

        service.indexArchives("test", ArchiveVectorLibrary.RECENT_24H, List.of(first, second), "group-1");

        List<ArchiveEmbeddingService.ArchiveVectorMatch> matches = service.searchArchiveMatches(
                "test",
                ArchiveVectorLibrary.RECENT_24H,
                "recent hit one",
                3,
                0.1
        );

        assertFalse(matches.isEmpty());
        ArchiveEmbeddingService.ArchiveVectorMatch topMatch = matches.getFirst();
        assertEquals(first.getId(), topMatch.archive().getId());
        assertEquals("group-1", topMatch.groupId());
        assertEquals(first.getId(), topMatch.headArchiveId());
        assertEquals(0, topMatch.groupOrder());
        assertEquals(
                MemoryVectorDocumentIds.recent24hDocumentId("group-1", first.getId()),
                topMatch.vectorDocumentId()
        );
    }

    private MemoryArchiveDocument archive(String sessionId, String groupId, String keywordSummary) {
        MemoryArchiveDocument archive = new MemoryArchiveDocument();
        archive.setSessionId(sessionId);
        archive.setGroupId(groupId);
        archive.setKeywordSummary(keywordSummary);
        archive.setNarrative(keywordSummary);
        archive.setTopic(keywordSummary);
        return archive;
    }

    private static final class TestEmbeddingModel implements EmbeddingModel {

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            return Response.from(segments.stream()
                    .map(TextSegment::text)
                    .map(this::embeddingFor)
                    .toList());
        }

        private Embedding embeddingFor(String text) {
            if (text == null) {
                return Embedding.from(new float[]{0f, 0f, 1f});
            }
            if (text.contains("one")) {
                return Embedding.from(new float[]{1f, 0f, 0f});
            }
            if (text.contains("two")) {
                return Embedding.from(new float[]{0f, 1f, 0f});
            }
            return Embedding.from(new float[]{0f, 0f, 1f});
        }
    }
}
