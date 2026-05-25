package p1.component.agent.rp.context;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import p1.config.prop.AssistantProperties;
import p1.utils.PromptTimeBucketUtil;

import java.time.LocalDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class SummaryCacheManager {

    private final AssistantProperties props;
    private final Map<String, LinkedList<SummaryEntry>> summaryCache = new ConcurrentHashMap<>();

    public String getSummary(String sessionId) {
        List<SummaryEntry> queue = summaryCache.get(sessionId);
        if (queue == null || queue.isEmpty()) {
            return "（暂时无摘要）";
        }

        StringBuilder builder = new StringBuilder("以下是之前的对话摘要：\n");
        for (int index = 0; index < queue.size(); index++) {
            SummaryEntry entry = queue.get(index);
            builder.append("摘要 ")
                    .append(index + 1)
                    .append(": ")
                    .append(entry.summary())
                    .append("\n时间：")
                    .append(PromptTimeBucketUtil.formatQuarterHour(entry.createdAt()))
                    .append("\n");
        }
        return builder.toString();
    }

    public void updateSummary(String sessionId, String newSummary) {
        updateSummary(sessionId, newSummary, LocalDateTime.now());
    }

    public void updateSummary(String sessionId, String newSummary, LocalDateTime createdAt) {
        if (newSummary == null || newSummary.isBlank()) {
            return;
        }

        summaryCache.computeIfAbsent(sessionId, ignored -> new LinkedList<>());
        LinkedList<SummaryEntry> queue = summaryCache.get(sessionId);
        if (queue.size() >= props.getChatMemory().getContextMaxSummaryCount()) {
            queue.removeFirst();
        }
        queue.addLast(new SummaryEntry(newSummary.trim(), createdAt == null ? LocalDateTime.now() : createdAt));
    }

    public void clear(String sessionId) {
        summaryCache.remove(sessionId);
    }

    private record SummaryEntry(String summary, LocalDateTime createdAt) {
    }
}
