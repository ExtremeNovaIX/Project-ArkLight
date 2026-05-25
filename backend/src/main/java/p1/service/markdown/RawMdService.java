package p1.service.markdown;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import p1.infrastructure.markdown.assembler.RawMdAssembler;
import p1.infrastructure.markdown.model.DialogueBatchMessage;
import p1.infrastructure.markdown.model.MarkdownDocument;
import p1.infrastructure.markdown.model.RawBatchDocument;
import p1.infrastructure.markdown.model.RawDialogueMessageRef;
import p1.model.enums.MessageRole;
import p1.service.lock.SessionLockExecutor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 用于处理data/raw目录下的原始对话文件。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RawMdService {

    private static final DateTimeFormatter BATCH_ID_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Duration COLLECTING_REOPEN_WINDOW = Duration.ofMinutes(30);

    private final RawDialogueStore rawDialogueStore;
    private final DialogueBatchStore dialogueBatchStore;
    private final RawMdAssembler rawMdAssembler;
    private final SessionLockExecutor sessionLockExecutor;

    /**
     * 追加一条新消息。
     * 这里必须把 raw daily note 和 collecting backlog 放在同一个 session 锁里，
     * 否则并发写入时可能出现只写入一边的中间态。
     */
    public RawDialogueMessageRef appendRawMessage(String sessionId,
                                                  MessageRole role,
                                                  String text) {
        String cleanText = normalizeText(text);
        if (cleanText.isBlank()) {
            throw new IllegalArgumentException("raw dialogue text must not be blank");
        }

        LocalDateTime lifecycleTime = LocalDateTime.now();
        var date = lifecycleTime.toLocalDate();

        return sessionLockExecutor.execute(sessionId, "appendDialogueMessage", () -> {
            String messageId = buildMessageId();
            MarkdownDocument initialDailyNote = rawMdAssembler.createDailyNote(sessionId, date);
            rawDialogueStore.createDailyNoteIfMissing(sessionId, date, initialDailyNote);
            rawDialogueStore.appendToDailyNote(
                    sessionId,
                    date,
                    rawMdAssembler.buildMessageBlock(role, cleanText, lifecycleTime, messageId)
            );

            RawDialogueMessageRef rawRef = new RawDialogueMessageRef(
                    rawDialogueStore.relativeDailyNotePath(sessionId, date),
                    messageId,
                    role,
                    lifecycleTime
            );

            // collecting.md 是当前 session 的持久 backlog。
            // 30 分钟内重连继续复用；超时后如果没有 processing，则把旧 collecting 封口成 processing。
            // raw daily note 是完整来源；collecting 只是压缩前的待处理队列，两者必须一起推进。
            Optional<RawBatchDocument> collectingCandidate = loadCollectingForAppend(sessionId, lifecycleTime);
            RawBatchDocument collecting = collectingCandidate.orElseGet(() -> {
                RawBatchDocument newCollecting = new RawBatchDocument(
                        buildBatchId(),
                        sessionId,
                        "collecting",
                        lifecycleTime,
                        lifecycleTime,
                        null,
                        new ArrayList<>()
                );
                log.info("[对话批次] sessionId={} 新建 collecting，batchId={}，createdAt={}",
                        sessionId, newCollecting.id(), lifecycleTime);
                return newCollecting;
            });

            List<DialogueBatchMessage> messages = new ArrayList<>(collecting.messages());
            messages.add(new DialogueBatchMessage(
                    rawRef.messageId(),
                    role,
                    cleanText,
                    lifecycleTime,
                    rawRef.notePath(),
                    rawRef.messageId()
            ));

            RawBatchDocument updatedCollecting = new RawBatchDocument(
                    collecting.id(),
                    sessionId,
                    "collecting",
                    collecting.createdAt(),
                    lifecycleTime,
                    null,
                    messages
            );
            dialogueBatchStore.saveCollecting(sessionId, updatedCollecting);
            log.debug("[对话批次] sessionId={} collecting 已追加消息，batchId={}，当前消息数={}",
                    sessionId, updatedCollecting.id(), updatedCollecting.messageCount());
            return rawRef;
        });
    }

    public Optional<RawBatchDocument> findProcessing(String sessionId) {
        return dialogueBatchStore.findProcessing(sessionId);
    }

    public boolean hasProcessingBatch(String sessionId) {
        return sessionLockExecutor.execute(sessionId, "hasProcessingBatch", () ->
                dialogueBatchStore.findProcessing(sessionId).isPresent()
        );
    }

    public int getCollectingMessageCount(String sessionId) {
        return sessionLockExecutor.execute(sessionId, "getCollectingMessageCount", () ->
                loadCollecting(sessionId).map(RawBatchDocument::messageCount).orElse(0)
        );
    }

    /**
     * 如果 collecting 达到阈值，就生成 processing 快照。
     * 这里不会立刻改写 collecting，而是等压缩成功后再正式扣除，避免中途失败时意外丢失。
     */
    public Optional<RawBatchDocument> promoteCollectingToProcessingIfReady(String sessionId, int triggerThreshold, int compressCount) {
        return sessionLockExecutor.execute(sessionId, "promoteCollectingToProcessingIfReady", () -> {
            log.info("[对话批次] 开始处理 sessionId={} 的 processing", sessionId);

            Optional<RawBatchDocument> existingProcessing = findProcessing(sessionId);
            if (existingProcessing.isPresent()) {
                RawBatchDocument processing = existingProcessing.get();
                log.info("[对话批次] sessionId={} 已存在 processing，直接复用，batchId={}，消息数={}",
                        sessionId, processing.id(), processing.messageCount());
                return existingProcessing;
            }

            RawBatchDocument collecting = loadCollectingForProcessing(sessionId, LocalDateTime.now()).orElse(null);
            if (collecting == null) {
                log.info("[对话批次] sessionId={} 未找到collecting，尝试从 processing 恢复", sessionId);
                return findProcessing(sessionId);
            }
            if (collecting.messageCount() < triggerThreshold) {
                log.info("[对话批次] sessionId={} 的 collecting 尚未达到阈值，batchId={}，当前消息数={}，阈值={}",
                        sessionId, collecting.id(), collecting.messageCount(), triggerThreshold);
                return Optional.empty();
            }

            // processing 只快照当前这一轮要压缩的头部消息。
            // 真正扣除 collecting，要等 acknowledgeProcessing 成功后再做。
            // processing 只冻结本轮准备压缩的头部消息，剩余 collecting 仍然保留，等待成功确认后再扣减。
            int processingCount = Math.min(compressCount, collecting.messageCount());
            List<DialogueBatchMessage> processingMessages = new ArrayList<>(collecting.messages().subList(0, processingCount));
            RawBatchDocument processing = new RawBatchDocument(
                    collecting.id(),
                    sessionId,
                    "processing",
                    collecting.createdAt(),
                    LocalDateTime.now(),
                    LocalDateTime.now(),
                    processingMessages
            );
            dialogueBatchStore.saveProcessing(sessionId, processing);
            log.info("[对话批次] sessionId={} collecting 已转为 processing，batchId={}，本次压缩消息数={}，collecting 总消息数={}",
                    sessionId, processing.id(), processing.messageCount(), collecting.messageCount());
            return Optional.of(processing);
        });
    }

    /**
     * 压缩成功后的确认步骤。
     * 这里是“从 collecting 扣掉已处理消息，再删除 processing”，
     * 目的是保证崩溃时可以恢复。
     */
    public void acknowledgeProcessing(String sessionId) {
        sessionLockExecutor.execute(sessionId, "acknowledgeProcessing", () -> {
            RawBatchDocument processing = findProcessing(sessionId).orElse(null);
            if (processing == null) {
                log.info("[对话批次] sessionId={} acknowledge 跳过，processing 不存在", sessionId);
                return;
            }

            RawBatchDocument collecting = loadCollecting(sessionId).orElse(null);
            if (collecting != null) {
                Set<String> processedIds = new LinkedHashSet<>();
                processing.messages().forEach(message -> processedIds.add(message.messageId()));

                // acknowledge 阶段做的不是“重建 collecting”，而是从旧 backlog 中精确扣掉已成功落库的那一段。
                List<DialogueBatchMessage> remainingMessages = collecting.messages().stream()
                        .filter(message -> !processedIds.contains(message.messageId()))
                        .toList();

                if (remainingMessages.isEmpty()) {
                    dialogueBatchStore.deleteCollecting(sessionId);
                    log.info("[对话批次] sessionId={} processing 确认完成，collecting 已清空，batchId={}",
                            sessionId, processing.id());
                } else {
                    RawBatchDocument updatedCollecting = new RawBatchDocument(
                            collecting.id(),
                            sessionId,
                            "collecting",
                            collecting.createdAt(),
                            LocalDateTime.now(),
                            null,
                            remainingMessages
                    );
                    dialogueBatchStore.saveCollecting(sessionId, updatedCollecting);
                    log.info("[对话批次] sessionId={} processing 确认完成，collecting 已扣除已处理消息，batchId={}，剩余消息数={}",
                            sessionId, processing.id(), updatedCollecting.messageCount());
                }
            } else {
                log.info("[对话批次] sessionId={} processing 确认完成，本轮为独立 processing，batchId={}",
                        sessionId, processing.id());
            }

            dialogueBatchStore.deleteProcessing(sessionId);
            log.info("[对话批次] sessionId={} processing 已删除，batchId={}", sessionId, processing.id());
        });
    }

    /**
     * 返回当前仍然存在 collecting 或 processing 的 session。
     * 启动恢复时用它来确定还有哪些 session 存在未完成任务。
     */
    public Set<String> listSessionIdsWithOpenBatches() {
        return dialogueBatchStore.listSessionIdsWithOpenBatches();
    }

    private Optional<RawBatchDocument> loadCollecting(String sessionId) {
        return dialogueBatchStore.findCollecting(sessionId);
    }

    /**
     * 读取“当前仍可继续追加”的 collecting。
     * 超时且没有 processing 时，会把旧 collecting 整体封口为 processing；
     * 超时但已有 processing 时，暂时保留 collecting，避免 backlog 被覆盖。
     */
    private Optional<RawBatchDocument> loadCollectingForAppend(String sessionId, LocalDateTime referenceTime) {
        RawBatchDocument collecting = loadCollecting(sessionId).orElse(null);
        if (collecting == null) {
            return Optional.empty();
        }
        if (!isCollectingExpired(collecting, referenceTime)) {
            log.debug("[对话批次] 复用 collecting，sessionId={}，batchId={}，lastActiveAt={}",
                    sessionId, collecting.id(), collecting.updatedAt() != null ? collecting.updatedAt() : collecting.createdAt());
            return Optional.of(collecting);
        }
        if (findProcessing(sessionId).isPresent()) {
            log.info("[对话批次] collecting 已超时，但已有 processing 在执行，暂不封口 collecting，sessionId={}，batchId={}",
                    sessionId, collecting.id());
            return Optional.of(collecting);
        }

        sealCollectingToProcessing(sessionId, collecting, referenceTime, "超时重连，旧 collecting 封口");
        return Optional.empty();
    }

    /**
     * 读取“当前可进入压缩判断”的 collecting。
     * collecting 超时且当前没有 processing 时，会直接整体转为 processing；
     * 如果已经有 processing，在它完成前不再创建第二个 processing。
     */
    private Optional<RawBatchDocument> loadCollectingForProcessing(String sessionId, LocalDateTime referenceTime) {
        RawBatchDocument collecting = loadCollecting(sessionId).orElse(null);
        if (collecting == null) {
            return Optional.empty();
        }
        if (!isCollectingExpired(collecting, referenceTime)) {
            return Optional.of(collecting);
        }
        if (findProcessing(sessionId).isPresent()) {
            log.info("[对话批次] collecting 已超时，但已有 processing 在执行，等待当前 processing 完成后再处理 collecting，sessionId={}，batchId={}",
                    sessionId, collecting.id());
            return Optional.empty();
        }

        sealCollectingToProcessing(sessionId, collecting, referenceTime, "collecting 超时，直接转 processing");
        return Optional.empty();
    }

    /**
     * 把一个超时 collecting 整体封口成 processing。
     * 因为它的整批内容已经完整移交给 processing，所以这里会直接删除原 collecting。
     */
    private void sealCollectingToProcessing(String sessionId,
                                            RawBatchDocument collecting,
                                            LocalDateTime referenceTime,
                                            String reason) {
        RawBatchDocument processing = new RawBatchDocument(
                collecting.id(),
                sessionId,
                "processing",
                collecting.createdAt(),
                referenceTime,
                referenceTime,
                new ArrayList<>(collecting.messages())
        );
        dialogueBatchStore.saveProcessing(sessionId, processing);
        dialogueBatchStore.deleteCollecting(sessionId);
        log.info("[对话批次] collecting 已转为 processing，sessionId={}，batchId={}，消息数={}，reason={}",
                sessionId, processing.id(), processing.messageCount(), reason);
    }

    private boolean isCollectingExpired(RawBatchDocument collecting, LocalDateTime referenceTime) {
        LocalDateTime lastActiveAt = collecting.updatedAt() != null ? collecting.updatedAt() : collecting.createdAt();
        if (lastActiveAt == null) {
            return false;
        }
        return lastActiveAt.plus(COLLECTING_REOPEN_WINDOW).isBefore(referenceTime);
    }

    private String buildMessageId() {
        return "dm-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String buildBatchId() {
        return "batch-" + BATCH_ID_TIME_FORMATTER.format(LocalDateTime.now()) + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private String normalizeText(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.strip()
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }
}
