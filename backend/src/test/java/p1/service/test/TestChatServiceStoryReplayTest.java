package p1.service.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import p1.service.ChatService;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TestChatServiceStoryReplayTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldReplayStoryChunksThroughFrontendChain() throws Exception {
        Path storyPath = tempDir.resolve("story.md");
        Files.writeString(storyPath, "A long enough story body to force chunked replay across the frontend chat chain.");

        ChatService chatService = mock(ChatService.class);
        when(chatService.sendMsgToRpAgent(any())).thenReturn("received");

        TestChatService service = new TestChatService(
                chatService,
                new StoryMarkdownChunker()
        );

        StoryReplayResult response = service.replayStory(storyPath, "story-session", "test", 40);

        assertTrue(response.chunkCount() >= 1);
        assertEquals(40, response.targetLength());
        assertEquals(40, response.effectiveSourceLength());
        assertEquals(response.chunkCount(), response.userMessageCount());
        assertEquals(response.chunkCount(), response.assistantMessageCount());
        assertEquals(Math.min(3, response.chunkCount()), response.previewChunks().size());
        verify(chatService, times(response.chunkCount())).sendMsgToRpAgent(any());
    }

    @Test
    void shouldPreferMarkdownAndFallbackToTxt() throws Exception {
        TestChatService service = new TestChatService(
                mock(ChatService.class),
                new StoryMarkdownChunker()
        );

        Path markdownOnlyDir = tempDir.resolve("markdown-only");
        Files.createDirectories(markdownOnlyDir);
        Path markdownPath = markdownOnlyDir.resolve("test.md");
        Files.writeString(markdownPath, "markdown");
        assertEquals(markdownPath, service.resolveStoryPath(markdownOnlyDir));

        Path txtOnlyDir = tempDir.resolve("txt-only");
        Files.createDirectories(txtOnlyDir);
        Path txtPath = txtOnlyDir.resolve("test.txt");
        Files.writeString(txtPath, "txt");
        assertEquals(txtPath, service.resolveStoryPath(txtOnlyDir));

        Path bothDir = tempDir.resolve("both");
        Files.createDirectories(bothDir);
        Path preferredMarkdown = bothDir.resolve("test.md");
        Path fallbackTxt = bothDir.resolve("test.txt");
        Files.writeString(preferredMarkdown, "markdown");
        Files.writeString(fallbackTxt, "txt");
        assertEquals(preferredMarkdown, service.resolveStoryPath(bothDir));
    }
}