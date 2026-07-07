package p1.component.agent.memory;

import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.ChatMemory;
import lombok.CustomLog;
import p1.infrastructure.logging.LogDomain;
import p1.infrastructure.logging.LogOutcome;
import p1.component.agent.memory.model.DialogueBatch;
import p1.config.prop.AssistantProperties;
import p1.config.prop.LockProperties;
import p1.infrastructure.markdown.model.DialogueBatchMessage;
import p1.model.ChatLogEntity;
import p1.service.ChatLogRepository;
import p1.service.markdown.RawMdService;
import p1.utils.ChatMessageUtil;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

@CustomLog
public class ArchivableChatMemory implements ChatMemory {

    private final String sessionId;
    private final List<ChatMessage> contextWindow = new CopyOnWriteArrayList<>();
    private final AtomicReference<CompressionLease> activeCompression = new AtomicReference<>();

    private final MemoryAsyncCompressor compressor;
    private final ChatMemoryAppender chatMemoryAppender;
    private final RawMdService rawMdService;
    private final ChatLogRepository chatLogRepository;
    private final ChatMemoryWritePolicy writePolicy;

    private final int triggerThreshold;
    private final int compressCount;
    private final Duration compressionLeaseTimeout;

    public ArchivableChatMemory(String sessionId,
                                MemoryAsyncCompressor compressor,
                                ChatMemoryAppender chatMemoryAppender,
                                RawMdService rawMdService,
                                AssistantProperties assistantProperties,
                                LockProperties lockProperties,
                                ChatLogRepository chatLogRepository) {
        this(sessionId, compressor, chatMemoryAppender, rawMdService, assistantProperties,
                lockProperties, chatLogRepository, new ChatMemoryWritePolicy());
    }

    public ArchivableChatMemory(String sessionId,
                                MemoryAsyncCompressor compressor,
                                ChatMemoryAppender chatMemoryAppender,
                                RawMdService rawMdService,
                                AssistantProperties assistantProperties,
                                LockProperties lockProperties,
                                ChatLogRepository chatLogRepository,
                                ChatMemoryWritePolicy writePolicy) {
        this.sessionId = sessionId;
        this.compressor = compressor;
        this.chatMemoryAppender = chatMemoryAppender;
        this.rawMdService = rawMdService;
        this.writePolicy = writePolicy;
        this.triggerThreshold = assistantProperties.getChatMemory().getTriggerCompressThreshold();
        this.compressCount = assistantProperties.getChatMemory().getCompressCount();
        this.compressionLeaseTimeout = Duration.ofMillis(lockProperties.getCompressionLeaseTimeoutMs());
        this.chatLogRepository = chatLogRepository;
    }

    @Override
    public Object id() {
        return sessionId;
    }

    @Override
    public void add(ChatMessage message) {
        if (message == null) {
            return;
        }
        if (writePolicy.shouldSuppress(sessionId, message)) {
            log.debug("[RP记忆] 忽略运行时上下文消息: sessionId={}, type={}", sessionId, message.type());
            return;
        }
        log.debug("消息队列新增: {}", message);
        // 保存消息到日志库
        ChatLogEntity entity = new ChatLogEntity(message, sessionId);
        chatLogRepository.save(entity);

        // 保持系统提示词置顶
        if (message instanceof SystemMessage) {
            contextWindow.removeIf(existing -> existing instanceof SystemMessage);
            contextWindow.addFirst(message);
            return;
        }

        // 只有 AI 最终回复才代表一轮对话闭合，此时才适合清理工具中间态并判断是否压缩。
        boolean isFinalTurnMessage = ChatMessageUtil.isAiFinalResponseMessage(message);
        if (isFinalTurnMessage) {
            log.trace("对话轮次结束，开始清理上下文。");
            purifyContext();
        }

        // markdown负责持久化，内存窗口给当前对话提供上下文。
        chatMemoryAppender.appendToRaw(sessionId, message);
        contextWindow.add(message);

        compressMemory(isFinalTurnMessage);
    }

    private void compressMemory(boolean isFinalTurnMessage) {
        if (!isFinalTurnMessage) {
            return;
        }
        //TODO 后续或许可以改成token水位和消息条数双阈值任选其一触发
        int windowCount = contextWindow.size();
        int collectingCount = rawMdService.getCollectingMessageCount(sessionId);
        boolean hasProcessingBatch = rawMdService.hasProcessingBatch(sessionId);
        if (collectingCount < triggerThreshold && !hasProcessingBatch) {
            log.debug("[记忆压缩] collecting 尚未达到阈值，暂不触发压缩，sessionId={}，windowCount={}，collectingCount={}，threshold={}",
                    sessionId, windowCount, collectingCount, triggerThreshold);
            return;
        }

        // lease 的作用是避免同一个 session 同时生成多个 processing 批次。
        CompressionLease lease = tryAcquireCompressionLease();
        if (lease == null) return;
        log.info(LogDomain.MEMORY, "memory.compress.triggered", LogOutcome.SUCCEEDED, "sessionId", sessionId, "leaseId", lease.leaseId(), "windowCount", windowCount, "collectingCount", collectingCount, "triggerThreshold", triggerThreshold, "hasProcessingBatch", hasProcessingBatch);

        DialogueBatch processingBatch;
        try {
            processingBatch = rawMdService
                    .promoteCollectingToProcessingIfReady(sessionId, triggerThreshold, compressCount)
                    .map(batch -> new DialogueBatch(batch.id(), batch.sessionId(), batch.messages()))
                    .orElse(null);
        } catch (Exception e) {
            log.error(LogDomain.MEMORY, "memory.compress.processing_prepare_failed", LogOutcome.FAILED, e, "sessionId", sessionId, "leaseId", lease.leaseId());
            releaseCompressionLease(lease, "准备 processing 失败");
            return;
        }

        if (processingBatch == null) {
            log.warn(LogDomain.MEMORY, "memory.compress.processing_missing", LogOutcome.DEGRADED, "sessionId", sessionId, "leaseId", lease.leaseId(), "windowCount", windowCount, "collectingCount", collectingCount);
            releaseCompressionLease(lease, "没有 processing 批次");
            return;
        }

        // processing 是 backlog 的只读快照，真正交给 AI 压缩的就是这一批已经冻结的消息。
        List<ChatMessage> toCompress = processingBatch.messages().stream()
                .map(DialogueBatchMessage::toChatMessage)
                .toList();

        compressor.compressAsync(sessionId, toCompress, () -> onCompressionSuccess(lease, processingBatch), () -> {
            if (isLeaseCurrent(lease)) {
                log.warn(LogDomain.MEMORY, "memory.compress.background_failed", LogOutcome.DEGRADED, "sessionId", sessionId, "batchId", processingBatch.batchId(), "leaseId", lease.leaseId());
                releaseCompressionLease(lease, "后台压缩失败");
            } else {
                log.warn(LogDomain.MEMORY, "memory.compress.processing_missing", LogOutcome.DEGRADED, "sessionId", sessionId, "batchId", processingBatch.batchId(), "leaseId", lease.leaseId());
            }
        });
    }

    private void onCompressionSuccess(CompressionLease lease, DialogueBatch processingBatch) {
        if (!isLeaseCurrent(lease)) {
            log.warn(LogDomain.MEMORY, "memory.compress.background_failed", LogOutcome.DEGRADED, "sessionId", sessionId, "leaseId", lease.leaseId());
            return;
        }

        try {
            // 只有压缩链路整体成功后，才正式确认 processing 并从窗口中扣减对应消息。
            rawMdService.acknowledgeProcessing(sessionId);
            int removedCount = removeProcessedMessagesFromWindow(processingBatch);
            log.info(LogDomain.MEMORY, "memory.compress.completed", LogOutcome.SUCCEEDED, "contextWindowCount", contextWindow.size(), "removedCount", removedCount, "sessionId", sessionId, "batchId", processingBatch.batchId(), "leaseId", lease.leaseId());
        } catch (Exception e) {
            log.error(LogDomain.MEMORY, "memory.compress.processing_prepare_failed", LogOutcome.FAILED, e, "sessionId", sessionId, "leaseId", lease.leaseId());
        } finally {
            releaseCompressionLease(lease, "压缩流程结束");
        }
    }

    private int removeProcessedMessagesFromWindow(DialogueBatch processingBatch) {
        int targetCount = processingBatch.messages().size();
        int removedCount = 0;

        for (int i = 0; i < contextWindow.size() && removedCount < targetCount; ) {
            ChatMessage message = contextWindow.get(i);
            boolean isPersistedMessage = message instanceof UserMessage
                    || (message instanceof AiMessage aiMessage && ChatMessageUtil.isAiFinalResponseMessage(aiMessage));
            if (isPersistedMessage) {
                contextWindow.remove(i);
                removedCount++;
                continue;
            }
            i++;
        }

        if (removedCount < targetCount) {
            log.warn(LogDomain.MEMORY, "memory.compress.stale_failure_ignored", LogOutcome.DEGRADED, "sessionId", sessionId, "batchId", processingBatch.batchId(), "targetCount", targetCount, "removedCount", removedCount);
        }
        return removedCount;
    }

    private CompressionLease tryAcquireCompressionLease() {
        LocalDateTime now = LocalDateTime.now();
        CompressionLease current = activeCompression.get();
        if (current == null) {
            CompressionLease newLease = new CompressionLease(UUID.randomUUID().toString(), now);
            return activeCompression.compareAndSet(null, newLease) ? newLease : null;
        }

        if (!isLeaseExpired(current, now)) {
            log.debug("[记忆压缩] 当前已有压缩任务执行中，跳过本次触发，sessionId={}，leaseId={}，startedAt={}",
                    sessionId, current.leaseId(), current.startedAt());
            return null;
        }

        // 超时接管只替换 lease，不会直接覆盖 processing；真正状态恢复仍由 markdown 批次状态决定。
        CompressionLease replacement = new CompressionLease(UUID.randomUUID().toString(), now);
        if (activeCompression.compareAndSet(current, replacement)) {
            log.warn(LogDomain.MEMORY, "memory.compress.stale_success_ignored", LogOutcome.DEGRADED, "sessionId", sessionId, "leaseId", current.leaseId(), "leaseId2", replacement.leaseId(), "startedAt", current.startedAt(), "toMillis", compressionLeaseTimeout.toMillis());
            return replacement;
        }

        return null;
    }

    private boolean isLeaseCurrent(CompressionLease lease) {
        return lease != null && lease.equals(activeCompression.get());
    }

    private boolean isLeaseExpired(CompressionLease lease, LocalDateTime referenceTime) {
        return lease != null && lease.startedAt().plus(compressionLeaseTimeout).isBefore(referenceTime);
    }

    private void releaseCompressionLease(CompressionLease lease, String reason) {
        if (lease == null) {
            return;
        }

        if (activeCompression.compareAndSet(lease, null)) {
            log.info(LogDomain.MEMORY, "memory.compress.lease_released", LogOutcome.SUCCEEDED, "sessionId", sessionId, "leaseId", lease.leaseId(), "reason", reason);
        }
    }

    private void purifyContext() {
        // 只清理“上一轮工具调用尾巴”，保留最近一个用户问题之前的稳定上下文。
        for (int i = contextWindow.size() - 1; i >= 0; i--) {
            ChatMessage message = contextWindow.get(i);

            if (message instanceof ToolExecutionResultMessage) {
                contextWindow.remove(i);
            } else if (message instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()) {
                contextWindow.remove(i);
            } else if (message instanceof UserMessage) {
                break;
            }
        }
        log.info(LogDomain.MEMORY, "memory.context.purified", LogOutcome.SUCCEEDED, "contextWindowCount", contextWindow.size());
    }

    @Override
    public List<ChatMessage> messages() {
        return contextWindow;
    }

    @Override
    public void clear() {
        contextWindow.clear();
        activeCompression.set(null);
    }

    private record CompressionLease(String leaseId, LocalDateTime startedAt) {
    }
}
