package p1.service.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StoryMarkdownChunkerTest {

    @TempDir
    Path tempDir;

    private final StoryMarkdownChunker chunker = new StoryMarkdownChunker();

    @Test
    void shouldSplitByNearestSentenceEndingWithinExpectedRange() {
        String text = "a".repeat(299) + "!" + "b".repeat(250) + "!";

        List<String> chunks = chunker.chunkText(text);

        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.length() >= 200 && chunk.length() <= 400));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.equals(chunk.trim())));
    }

    @Test
    void shouldStripSimpleMarkdownNoiseBeforeChunking() {
        String markdown = """
                ---
                title: test
                ---

                # 第一章
                [旧港口](https://example.com) 的风一直很硬。
                > 她觉得今晚不太对劲。
                - 但她还是往前走了。
                """;

        List<String> chunks = chunker.chunkText(markdown);

        assertEquals(1, chunks.size());
        String onlyChunk = chunks.getFirst();
        assertFalse(onlyChunk.contains("---"));
        assertFalse(onlyChunk.contains("#"));
        assertFalse(onlyChunk.contains("https://example.com"));
        assertTrue(onlyChunk.contains("旧港口"));
    }

    @Test
    void shouldRespectTargetLengthBeforeChunking() {
        String text = """
                第一段写她在码头等人。第二段写她看见旧纸条。第三段写她决定第二天去白桥赴约。第四段写她出门前又犹豫了很久。
                """;

        String truncated = chunker.truncateNormalizedText(text, 18);
        List<String> chunks = chunker.chunkText(text, 18);

        assertEquals(18, truncated.length());
        assertEquals(1, chunks.size());
        assertEquals(truncated, chunks.getFirst());
    }

    @Test
    void shouldProcessStoryInStreamingModeWithoutLoadingAllChunksFirst() throws Exception {
        Path storyPath = tempDir.resolve("story.txt");
        Files.writeString(storyPath, """
                第一章
                她站在旧港口边上，盯着雨水打在栏杆上。

                第二章
                她忽然想起三年前那张没有寄出的明信片！！！！
                接着她又看见了远处慢慢靠近的身影。
                """);

        List<String> chunks = new ArrayList<>();
        StoryMarkdownChunker.ChunkingReport report = chunker.processStory(storyPath, 40, chunks::add);

        assertEquals(report.chunkCount(), chunks.size());
        assertEquals(40, report.effectiveSourceLength());
        assertTrue(report.previewChunks().size() <= 3);
        assertFalse(chunks.isEmpty());
    }
}
