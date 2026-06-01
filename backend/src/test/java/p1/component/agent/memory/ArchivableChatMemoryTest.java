package p1.component.agent.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import p1.config.prop.AssistantProperties;
import p1.config.prop.LockProperties;
import p1.infrastructure.markdown.model.RawBatchDocument;
import p1.service.ChatLogRepository;
import p1.service.markdown.RawMdService;
import p1.utils.ChatMessageUtil;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArchivableChatMemoryTest {

    @Test
    void shouldSuppressUserMessageOnlyWhenUpstreamDeclaresTransientScope() {
        MemoryAsyncCompressor compressor = mock(MemoryAsyncCompressor.class);
        ChatMemoryAppender chatMemoryAppender = mock(ChatMemoryAppender.class);
        RawMdService rawMdService = mock(RawMdService.class);
        ChatLogRepository chatLogRepository = mock(ChatLogRepository.class);
        ChatMemoryWritePolicy writePolicy = new ChatMemoryWritePolicy();

        AssistantProperties assistantProperties = new AssistantProperties();
        AssistantProperties.ChatMemoryConfig chatMemoryConfig = new AssistantProperties.ChatMemoryConfig();
        chatMemoryConfig.setTriggerCompressThreshold(99);
        chatMemoryConfig.setCompressCount(99);
        assistantProperties.setChatMemory(chatMemoryConfig);
        LockProperties lockProperties = new LockProperties();
        lockProperties.setCompressionLeaseTimeoutMs(60_000);

        ArchivableChatMemory chatMemory = new ArchivableChatMemory(
                "default",
                compressor,
                chatMemoryAppender,
                rawMdService,
                assistantProperties,
                lockProperties,
                chatLogRepository,
                writePolicy
        );

        writePolicy.suppressUserMessage("default", "transient_runtime", () -> {
            chatMemory.add(UserMessage.from("transient_runtime", "运行时触发。"));
            return null;
        });
        chatMemory.add(UserMessage.from("transient_runtime", "没有 scope 时仍按普通消息处理。"));
        chatMemory.add(UserMessage.from("game_runtime_event", "RP 动作解析失败，未执行动作。"));

        org.junit.jupiter.api.Assertions.assertEquals(2, chatMemory.messages().size());
        org.junit.jupiter.api.Assertions.assertTrue(
                chatMemory.messages().getFirst() instanceof UserMessage kept && "transient_runtime".equals(kept.name()));
        verify(chatLogRepository, times(2)).save(any());
        verify(chatMemoryAppender, times(2)).appendToRaw(eq("default"), any());
    }

    @Test
    void shouldKeepContextWindowMessagesUntimedForFrontendAssistant() {
        MemoryAsyncCompressor compressor = mock(MemoryAsyncCompressor.class);
        ChatMemoryAppender chatMemoryAppender = mock(ChatMemoryAppender.class);
        RawMdService rawMdService = mock(RawMdService.class);
        ChatLogRepository chatLogRepository = mock(ChatLogRepository.class);

        AssistantProperties assistantProperties = new AssistantProperties();
        AssistantProperties.ChatMemoryConfig chatMemoryConfig = new AssistantProperties.ChatMemoryConfig();
        chatMemoryConfig.setTriggerCompressThreshold(99);
        chatMemoryConfig.setCompressCount(99);
        assistantProperties.setChatMemory(chatMemoryConfig);
        LockProperties lockProperties = new LockProperties();
        lockProperties.setCompressionLeaseTimeoutMs(60_000);

        ArchivableChatMemory chatMemory = new ArchivableChatMemory(
                "default",
                compressor,
                chatMemoryAppender,
                rawMdService,
                assistantProperties,
                lockProperties,
                chatLogRepository
        );

        chatMemory.add(UserMessage.from("hello there"));

        List<dev.langchain4j.data.message.ChatMessage> messages = chatMemory.messages();
        org.junit.jupiter.api.Assertions.assertEquals(1, messages.size());
        org.junit.jupiter.api.Assertions.assertEquals("hello there", ChatMessageUtil.extractText(messages.getFirst()));
        verify(chatMemoryAppender).appendToRaw(eq("default"), argThat(message ->
                message != null && "hello there".equals(ChatMessageUtil.extractText(message))
        ));
    }

    @Test
    void shouldKeepRawRpControlOutputInMemory() {
        MemoryAsyncCompressor compressor = mock(MemoryAsyncCompressor.class);
        ChatMemoryAppender chatMemoryAppender = mock(ChatMemoryAppender.class);
        RawMdService rawMdService = mock(RawMdService.class);
        ChatLogRepository chatLogRepository = mock(ChatLogRepository.class);

        AssistantProperties assistantProperties = new AssistantProperties();
        AssistantProperties.ChatMemoryConfig chatMemoryConfig = new AssistantProperties.ChatMemoryConfig();
        chatMemoryConfig.setTriggerCompressThreshold(99);
        chatMemoryConfig.setCompressCount(99);
        assistantProperties.setChatMemory(chatMemoryConfig);
        LockProperties lockProperties = new LockProperties();
        lockProperties.setCompressionLeaseTimeoutMs(60_000);

        ArchivableChatMemory chatMemory = new ArchivableChatMemory(
                "default",
                compressor,
                chatMemoryAppender,
                rawMdService,
                assistantProperties,
                lockProperties,
                chatLogRepository
        );
        String raw = """
                <turn>
                <step 1>
                {"mode":"action","say":"先挡一下。","do":"打出防御"}
                </step 1>
                </turn>
                """.trim();

        chatMemory.add(AiMessage.from(raw));

        org.junit.jupiter.api.Assertions.assertEquals(raw, ChatMessageUtil.extractText(chatMemory.messages().getFirst()));
        verify(chatMemoryAppender).appendToRaw(eq("default"), argThat(message ->
                message != null && raw.equals(ChatMessageUtil.extractText(message))
        ));
    }

    @Test
    void shouldContinueCompressionWhenProcessingAlreadyExists() {
        MemoryAsyncCompressor compressor = mock(MemoryAsyncCompressor.class);
        ChatMemoryAppender chatMemoryAppender = mock(ChatMemoryAppender.class);
        RawMdService rawMdService = mock(RawMdService.class);
        ChatLogRepository chatLogRepository = mock(ChatLogRepository.class);

        AssistantProperties assistantProperties = new AssistantProperties();
        AssistantProperties.ChatMemoryConfig chatMemoryConfig = new AssistantProperties.ChatMemoryConfig();
        chatMemoryConfig.setTriggerCompressThreshold(16);
        chatMemoryConfig.setCompressCount(16);
        assistantProperties.setChatMemory(chatMemoryConfig);
        LockProperties lockProperties = new LockProperties();
        lockProperties.setCompressionLeaseTimeoutMs(60_000);

        RawBatchDocument batch = new RawBatchDocument(
                "batch-processing",
                "default",
                "processing",
                LocalDateTime.now(),
                LocalDateTime.now(),
                LocalDateTime.now(),
                List.of()
        );
        when(rawMdService.getCollectingMessageCount(anyString())).thenReturn(0);
        when(rawMdService.hasProcessingBatch(anyString())).thenReturn(true);
        when(rawMdService.promoteCollectingToProcessingIfReady(anyString(), anyInt(), anyInt()))
                .thenReturn(Optional.of(batch));
        doAnswer(invocation -> {
            Runnable onSuccess = invocation.getArgument(2);
            onSuccess.run();
            return null;
        }).when(compressor).compressAsync(anyString(), anyList(), any(), any());

        ArchivableChatMemory chatMemory = new ArchivableChatMemory(
                "default",
                compressor,
                chatMemoryAppender,
                rawMdService,
                assistantProperties,
                lockProperties,
                chatLogRepository
        );

        chatMemory.add(UserMessage.from("user message"));
        chatMemory.add(AiMessage.from("assistant message"));

        verify(compressor, times(1)).compressAsync(anyString(), anyList(), any(), any());
        verify(rawMdService, times(1)).acknowledgeProcessing("default");
    }

    @Test
    void shouldRetryCompressionAfterFailureCallbackReleasesLease() {
        MemoryAsyncCompressor compressor = mock(MemoryAsyncCompressor.class);
        ChatMemoryAppender chatMemoryAppender = mock(ChatMemoryAppender.class);
        RawMdService rawMdService = mock(RawMdService.class);
        ChatLogRepository chatLogRepository = mock(ChatLogRepository.class);

        AssistantProperties assistantProperties = new AssistantProperties();
        AssistantProperties.ChatMemoryConfig chatMemoryConfig = new AssistantProperties.ChatMemoryConfig();
        chatMemoryConfig.setTriggerCompressThreshold(2);
        chatMemoryConfig.setCompressCount(2);
        assistantProperties.setChatMemory(chatMemoryConfig);
        LockProperties lockProperties = new LockProperties();
        lockProperties.setCompressionLeaseTimeoutMs(60_000);

        RawBatchDocument batch = new RawBatchDocument(
                "batch-1",
                "default",
                "processing",
                LocalDateTime.now(),
                LocalDateTime.now(),
                LocalDateTime.now(),
                List.of()
        );
        when(rawMdService.promoteCollectingToProcessingIfReady(anyString(), anyInt(), anyInt()))
                .thenReturn(Optional.of(batch));
        when(rawMdService.getCollectingMessageCount(anyString())).thenReturn(2);

        AtomicInteger callCounter = new AtomicInteger();
        doAnswer(invocation -> {
            Runnable onSuccess = invocation.getArgument(2);
            Runnable onFailure = invocation.getArgument(3);
            if (callCounter.incrementAndGet() == 1) {
                onFailure.run();
            } else {
                onSuccess.run();
            }
            return null;
        }).when(compressor).compressAsync(anyString(), anyList(), any(), any());

        ArchivableChatMemory chatMemory = new ArchivableChatMemory(
                "default",
                compressor,
                chatMemoryAppender,
                rawMdService,
                assistantProperties,
                lockProperties,
                chatLogRepository
        );

        chatMemory.add(UserMessage.from("第一轮用户消息"));
        chatMemory.add(AiMessage.from("第一轮 AI 回复"));
        chatMemory.add(UserMessage.from("第二轮用户消息"));
        chatMemory.add(AiMessage.from("第二轮 AI 回复"));

        verify(compressor, times(2)).compressAsync(anyString(), anyList(), any(), any());
        verify(rawMdService, times(1)).acknowledgeProcessing("default");
    }
}
