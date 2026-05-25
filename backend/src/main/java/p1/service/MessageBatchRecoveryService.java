package p1.service;

import dev.langchain4j.data.message.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
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
@Slf4j
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

        log.info("[对话恢复] 发现 {} 个 session 存在未完成的对话批次，正在恢复...", sessionIds.size());
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
            log.warn("[对话恢复] sessionId={}, batchId={} 暂停启动恢复，yaml 中默认 AI 配置不可用，修正配置后再触发",
                    batch.sessionId(), batch.batchId());
            return;
        }

        List<ChatMessage> chatMessages = toChatMessages(batch.messages());
        if (chatMessages.isEmpty()) {
            log.warn("[对话恢复] sessionId={}, batchId={}, 没有消息恢复，跳过压缩",
                    batch.sessionId(), batch.batchId());
            return;
        }

        log.info("[对话恢复] sessionId={}, batchId={}, 待恢复消息数={}",
                batch.sessionId(), batch.batchId(), chatMessages.size());

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
        }, () -> log.warn("[对话恢复] sessionId={}, batchId={} 压缩失败，保留 processing 等待下次恢复",
                batch.sessionId(), batch.batchId()));
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
