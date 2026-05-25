package p1.service.test;

import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import p1.model.dto.ChatRequestDTO;
import p1.model.dto.StoryReplayRequestDTO;
import p1.model.dto.StoryReplayResultDTO;
import p1.service.ChatService;
import p1.utils.SessionUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class StoryReplayService {

    private static final Path STORY_DIRECTORY = Path.of("test");
    private static final String STORY_MARKDOWN_FILENAME = "test.md";
    private static final String STORY_TEXT_FILENAME = "test.txt";
    private static final String DEFAULT_STORY_SESSION_ID = "story-replay";
    private static final String DEFAULT_CHARACTER_NAME = "test";
    private static final int MAX_RETRY_COUNT = 3;
    private static final Duration LLM_RETRY_DELAY = Duration.ofSeconds(5);

    private final ChatService chatService;
    private final StoryMarkdownChunker storyMarkdownChunker;

    public StoryReplayResultDTO replayStoryFromFile(StoryReplayRequestDTO request) {
        String sessionId = SessionUtil.normalizeSessionId(firstText(
                request == null ? null : request.getSessionId(),
                DEFAULT_STORY_SESSION_ID
        ));
        String characterName = firstText(
                request == null ? null : request.getCharacterName(),
                DEFAULT_CHARACTER_NAME
        );
        Integer targetLength = request == null ? null : request.getTargetLength();
        return replayStory(resolveStoryPath(STORY_DIRECTORY), sessionId, characterName, targetLength);
    }

    private StoryReplayResultDTO replayStory(Path storyPath,
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
                String assistantReply = sendChatToLLMWithRetry(chatRequest, round);
                if (StringUtils.hasText(assistantReply)) {
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

        return new StoryReplayResultDTO(
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

    private String sendChatToLLMWithRetry(ChatRequestDTO chatRequest, int round) {
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRY_COUNT; attempt++) {
            try {
                return chatService.sendChatToLLM(chatRequest);
            } catch (RuntimeException exception) {
                lastException = exception;
                System.err.printf(
                        "story-replay LLM request failed | round=%d | attempt=%d/%d | retryIn=%ds | exception=%s | message=%s%n",
                        round,
                        attempt,
                        MAX_RETRY_COUNT,
                        LLM_RETRY_DELAY.toSeconds(),
                        exception.getClass().getName(),
                        exception.getMessage()
                );
                if (attempt < MAX_RETRY_COUNT) {
                    sleepBeforeRetry();
                }
            }
        }
        throw lastException == null ? new IllegalStateException("story-replay failed") : lastException;
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(LLM_RETRY_DELAY.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("story-replay retry interrupted", exception);
        }
    }

    private Path resolveStoryPath(Path storyDirectory) {
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

    private String firstText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
