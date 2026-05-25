package p1.service.test;

import org.slf4j.MDC;
import p1.model.dto.ChatRequestDTO;
import p1.service.ChatService;
import p1.utils.SessionUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

public class TestChatService {

    private static final Path STORY_DIRECTORY = Path.of("test");
    private static final String STORY_MARKDOWN_FILENAME = "test.md";
    private static final String STORY_TEXT_FILENAME = "test.txt";
    private static final String DEFAULT_STORY_SESSION_ID = "story-replay";
    private static final String DEFAULT_CHARACTER_NAME = "test";
    private static final Duration LLM_RETRY_DELAY = Duration.ofSeconds(30);

    private final ChatService chatService;
    private final StoryMarkdownChunker storyMarkdownChunker;

    public TestChatService(ChatService chatService, StoryMarkdownChunker storyMarkdownChunker) {
        this.chatService = chatService;
        this.storyMarkdownChunker = storyMarkdownChunker;
    }

    public StoryReplayResult replayStoryFromFile(String sessionId, Integer targetLength) {
        String normalizedSessionId = SessionUtil.normalizeSessionId(
                sessionId == null || sessionId.isBlank() ? DEFAULT_STORY_SESSION_ID : sessionId
        );
        return replayStory(
                resolveStoryPath(STORY_DIRECTORY),
                normalizedSessionId,
                DEFAULT_CHARACTER_NAME,
                targetLength
        );
    }

    StoryReplayResult replayStory(Path storyPath,
                                  String sessionId,
                                  String characterName,
                                  Integer targetLength) {
        AtomicInteger assistantReplyCount = new AtomicInteger();
        AtomicInteger roundCounter = new AtomicInteger();

        StoryMarkdownChunker.ChunkingReport report = storyMarkdownChunker.processStory(storyPath, targetLength, chunk -> {
            int round = roundCounter.incrementAndGet();

            ChatRequestDTO chatRequest = new ChatRequestDTO();
            chatRequest.setSessionId(sessionId);
            chatRequest.setMessage(chunk);
            chatRequest.setCharacterName(characterName);
            chatRequest.setShortMode(false);

            MDC.put("sessionId", sessionId);
            MDC.put("chatRound", String.valueOf(round));
            try {
                String assistantReply = sendChatToLLMUntilSuccess(chatRequest, round);
                if (assistantReply != null && !assistantReply.isBlank()) {
                    assistantReplyCount.incrementAndGet();
                }
            } finally {
                MDC.remove("chatRound");
                MDC.remove("sessionId");
            }
        });

        if (report.chunkCount() == 0) {
            throw new IllegalStateException("Story file produced no replay chunks: " + storyPath.toAbsolutePath());
        }

        return new StoryReplayResult(
                sessionId,
                storyPath.toAbsolutePath().toString(),
                targetLength,
                report.effectiveSourceLength(),
                report.chunkCount(),
                report.chunkCount(),
                assistantReplyCount.get(),
                report.minChunkLength(),
                report.maxChunkLength(),
                report.averageChunkLength(),
                report.previewChunks()
        );
    }

    private String sendChatToLLMUntilSuccess(ChatRequestDTO chatRequest, int round) {
        int attempt = 1;
        while (true) {
            try {
                return chatService.sendMsgToRpAgent(chatRequest);
            } catch (RuntimeException exception) {
                System.err.printf(
                        "story-replay LLM request failed | round=%d | attempt=%d | retryIn=%ds | exception=%s | message=%s%n",
                        round,
                        attempt,
                        LLM_RETRY_DELAY.toSeconds(),
                        exception.getClass().getName(),
                        exception.getMessage()
                );
                sleepBeforeRetry();
                attempt++;
            }
        }
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(LLM_RETRY_DELAY.toMillis());
        } catch (InterruptedException exception) {
            System.err.println("story-replay retry sleep interrupted; continuing retry loop.");
        }
    }

    Path resolveStoryPath(Path storyDirectory) {
        Path markdownPath = storyDirectory.resolve(STORY_MARKDOWN_FILENAME);
        if (Files.exists(markdownPath)) {
            return markdownPath;
        }

        Path textPath = storyDirectory.resolve(STORY_TEXT_FILENAME);
        if (Files.exists(textPath)) {
            return textPath;
        }

        throw new IllegalStateException("Missing story file. Expected: "
                + markdownPath.toAbsolutePath() + " or " + textPath.toAbsolutePath());
    }
}
