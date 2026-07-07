package p1.component.agent.memory;

import dev.langchain4j.data.message.ChatMessage;
import lombok.CustomLog;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import p1.infrastructure.logging.LogDomain;
import p1.infrastructure.logging.LogOutcome;
import p1.component.agent.memory.model.FactExtractionPipelineResult;
import p1.component.agent.rp.context.SummaryCacheManager;

import java.util.List;

@Service
@CustomLog
@RequiredArgsConstructor
public class MemoryAsyncCompressor {

    private final SummaryCacheManager summaryCacheManager;
    private final MemoryCompressionPipeline memoryCompressionPipeline;
    private final MemoryWriteService memoryStorage;

    /**
     * 异步压缩链路：
     * 1. 只保留用户消息和 AI 最终答复；
     * 2. 提取事件并补全重要性评分；
     * 3. 过滤低分事件；
     * 4. 以“一次压缩批次”为单位写入存储侧 event-group。
     */
    @Async("asyncTaskExecutor")
    public void compressAsync(String sessionId,
                              List<ChatMessage> toCompress,
                              Runnable onSuccess,
                              Runnable onFailure) {
        log.info(LogDomain.MEMORY, "memory.compress.async_started", LogOutcome.SUCCEEDED, "sessionId", sessionId, "messageCount", toCompress.size());
        try {
            FactExtractionPipelineResult extractionResult =
                    memoryCompressionPipeline.buildPipelineResult(sessionId, toCompress).orElse(null);
            if (extractionResult == null) {
                if (onSuccess != null) {
                    onSuccess.run();
                }
                return;
            }

            memoryStorage.saveEventGroup(sessionId, extractionResult.events(), extractionResult.tags());

            String normalizedSummary = extractionResult.summary() == null ? "" : extractionResult.summary().trim();
            if (!normalizedSummary.isBlank()) {
                summaryCacheManager.updateSummary(sessionId, normalizedSummary);
            }

            if (onSuccess != null) {
                onSuccess.run();
            }
        } catch (Exception e) {
            log.error(LogDomain.MEMORY, "memory.compress.async_failed", LogOutcome.FAILED, e, "sessionId", sessionId);
            if (onFailure != null) {
                onFailure.run();
            }
        }
    }
}
