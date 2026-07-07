package p1.service;

import dev.langchain4j.data.message.ChatMessage;
import lombok.CustomLog;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import p1.infrastructure.logging.LogDomain;
import p1.infrastructure.logging.LogOutcome;
import p1.component.agent.memory.MemoryAsyncCompressor;
import p1.component.agent.memory.model.DialogueBatch;
import p1.config.prop.AssistantProperties;
import p1.infrastructure.markdown.model.DialogueBatchMessage;
import p1.infrastructure.markdown.model.RawBatchDocument;
import p1.service.markdown.RawMdService;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@CustomLog
public class MessageBatchRecoveryService {

    private final MemoryAsyncCompressor memoryAsyncCompressor;
    private final RawMdService rawMdService;
    private final AssistantProperties assistantProperties;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverPendingDialogueMessages() {
        recoverMarkdownBatches();
    }

    /**
     * 启动恢复顺序：
     * 1. 优先恢复 processing.md，因为它代表上次已经进入执行中的批次。
     * 2. 如果没有 processing.md，再尝试把达到阈值的 collecting.md 提升为 processing.md。
     */
    private void recoverMarkdownBatches() {
        Set<String> sessionIds = rawMdService.listSessionIdsWithOpenBatches();
        if (sessionIds.isEmpty()) {
            return;
        }

        log.info(LogDomain.MEMORY, "batch.recovery_started", LogOutcome.SUCCEEDED, "sessionCount", sessionIds.size());
        for (String sessionId : sessionIds) {
            DialogueBatch processingBatch = rawMdService.findProcessing(sessionId)
                    .map(this::toDialogueBatch)
                    .orElse(null);
            if (processingBatch != null) {
                submitRecoveryCompression(processingBatch);
                continue;
            }

            rawMdService
                    .promoteCollectingToProcessingIfReady(
                            sessionId,
                            assistantProperties.getChatMemory().getTriggerCompressThreshold(),
                            assistantProperties.getChatMemory().getCompressCount()
                    )
                    .map(this::toDialogueBatch)
                    .ifPresent(this::submitRecoveryCompression);
        }
    }

    private void submitRecoveryCompression(DialogueBatch batch) {
        if (batch == null || batch.messages().isEmpty()) {
            return;
        }
        if (!canRecoverWithCurrentSettings()) {
            log.warn(LogDomain.MEMORY, "batch.recovery_model_unavailable", LogOutcome.DEGRADED, "sessionId", batch.sessionId(), "batchId", batch.batchId());
            return;
        }

        List<ChatMessage> chatMessages = toChatMessages(batch.messages());
        if (chatMessages.isEmpty()) {
            log.warn(LogDomain.MEMORY, "batch.recovery_model_unavailable", LogOutcome.DEGRADED, "sessionId", batch.sessionId(), "batchId", batch.batchId());
            return;
        }

        log.info(LogDomain.MEMORY, "batch.recovery_submitted", LogOutcome.SUCCEEDED, "sessionId", batch.sessionId(), "batchId", batch.batchId(), "messageCount", chatMessages.size());

        memoryAsyncCompressor.compressAsync(batch.sessionId(), chatMessages, () -> {
            rawMdService.acknowledgeProcessing(batch.sessionId());
            rawMdService
                    .promoteCollectingToProcessingIfReady(
                            batch.sessionId(),
                            assistantProperties.getChatMemory().getTriggerCompressThreshold(),
                            assistantProperties.getChatMemory().getCompressCount()
                    )
                    .map(this::toDialogueBatch)
                    .ifPresent(this::submitRecoveryCompression);
        }, () -> log.warn(LogDomain.MEMORY, "batch.recovery_empty", LogOutcome.SKIPPED, "sessionId", batch.sessionId(), "batchId", batch.batchId()));
    }

    private DialogueBatch toDialogueBatch(RawBatchDocument document) {
        return new DialogueBatch(document.id(), document.sessionId(), document.messages());
    }

    private List<ChatMessage> toChatMessages(List<DialogueBatchMessage> pendingMessages) {
        return pendingMessages.stream()
                .map(DialogueBatchMessage::toChatMessage)
                .toList();
    }

    private boolean canRecoverWithCurrentSettings() {
        AssistantProperties.ChatModelConfig chatModel = assistantProperties.activeChatModel();
        return chatModel != null
                && hasRealText(chatModel.getBaseUrl())
                && hasRealText(chatModel.getModelName())
                && hasRealText(chatModel.getApiKey())
                && !"default_value".equals(chatModel.getApiKey().trim());
    }

    private boolean hasRealText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
