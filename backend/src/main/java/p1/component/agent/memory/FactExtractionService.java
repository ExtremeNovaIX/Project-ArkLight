package p1.component.agent.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.output.structured.Description;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import p1.component.agent.memory.model.ExtractedMemoryEvent;
import p1.component.agent.memory.model.FactExtractionPipelineResult;
import p1.component.agent.rp.context.SummaryCacheManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class FactExtractionService {

    private final FactExtractionAiService factExtractionAiService;
    private final FactEvaluatorAiService factEvaluatorAiService;
    private final SummaryCacheManager summaryCacheManager;

    /**
     * 事件提取入口
     * 内部会进行两步流程，第一步为提取事件和评分，第二阶段为摘要和标签
     *
     * @return 提取到的事件列表
     */
    public List<ExtractedFactEventDTO> extractFact(List<ChatMessage> chatContext, String sessionId) {
        String backendSummary = summaryCacheManager.getSummary(sessionId);
        FactExtractionDTO extracted = factExtractionAiService.extractFacts(chatContext, backendSummary);
        return safeExtractedEvents(extracted);
    }

    public FactSummaryDTO summarizeFacts(List<ExtractedFactEventDTO> extractedEvents) {
        return summarizeFacts(extractedEvents, null);
    }

    public FactSummaryDTO summarizeFacts(List<ExtractedFactEventDTO> extractedEvents, String sessionId) {
        List<ExtractedFactEventDTO> safeEvents = extractedEvents == null ? List.of() : extractedEvents;
        if (safeEvents.isEmpty()) {
            return emptySummary();
        }
        return factEvaluatorAiService.evaluateAndSummarizeFacts(safeEvents);
    }

    public FactExtractionPipelineResult buildPipelineResult(List<ExtractedFactEventDTO> extractedEvents,
                                                            FactSummaryDTO summary) {
        List<ExtractedFactEventDTO> safeExtractedEvents = extractedEvents == null ? List.of() : extractedEvents;
        if (safeExtractedEvents.isEmpty()) {
            return new FactExtractionPipelineResult(List.of(), List.of(), "");
        }

        return new FactExtractionPipelineResult(
                mergePipelineEvents(safeExtractedEvents, safeSummaryEvents(summary)),
                normalizeTags(summary == null ? null : summary.getTags()),
                normalize(summary == null ? null : summary.getSummary())
        );
    }

    private FactSummaryDTO emptySummary() {
        FactSummaryDTO summary = new FactSummaryDTO();
        summary.setEvents(List.of());
        summary.setTags(List.of());
        summary.setSummary("");
        return summary;
    }

    private List<ExtractedFactEventDTO> safeExtractedEvents(FactExtractionDTO extracted) {
        if (extracted == null || extracted.payloadEvents().isEmpty()) {
            return List.of();
        }
        return extracted.payloadEvents();
    }

    private List<FactSummaryEventDTO> safeSummaryEvents(FactSummaryDTO summary) {
        if (summary == null || summary.payloadEvents().isEmpty()) {
            throw new IllegalArgumentException("第二阶段摘要结果为空");
        }
        return summary.payloadEvents();
    }

    private List<ExtractedMemoryEvent> mergePipelineEvents(List<ExtractedFactEventDTO> extractedEvents,
                                                           List<FactSummaryEventDTO> summaryEvents) {
        List<ExtractedFactEventDTO> safeExtractedEvents = extractedEvents == null ? List.of() : extractedEvents;
        List<FactSummaryEventDTO> safeSummaryEvents = summaryEvents == null ? List.of() : summaryEvents;

        if (safeExtractedEvents.size() != safeSummaryEvents.size()) {
            throw new IllegalArgumentException("第二阶段事件数量与第一阶段不一致");
        }

        List<ExtractedMemoryEvent> merged = new ArrayList<>();
        for (int i = 0; i < safeExtractedEvents.size(); i++) {
            ExtractedFactEventDTO extractedEvent = safeExtractedEvents.get(i);
            FactSummaryEventDTO summaryEvent = safeSummaryEvents.get(i);
            if (extractedEvent == null || summaryEvent == null) {
                throw new IllegalArgumentException("提取链路中出现空事件");
            }

            validateTopicAlignment(extractedEvent.getTopic(), summaryEvent.getTopic());
            validateImportanceScore(extractedEvent.getImportanceScore());

            ExtractedMemoryEvent dto = new ExtractedMemoryEvent();
            dto.setTopic(normalize(extractedEvent.getTopic()));
            dto.setNarrative(normalize(extractedEvent.getNarrative()));
            dto.setKeywordSummary(normalize(summaryEvent.getKeywordSummary()));
            dto.setImportanceScore(extractedEvent.getImportanceScore());
            merged.add(dto);
        }
        return merged;
    }

    private void validateTopicAlignment(String extractedTopic, String summaryTopic) {
        if (!normalize(extractedTopic).equals(normalize(summaryTopic))) {
            throw new IllegalArgumentException("第二阶段 topic 与第一阶段不一致");
        }
    }

    private void validateImportanceScore(int importanceScore) {
        if (importanceScore < 1 || importanceScore > 10) {
            throw new IllegalArgumentException("importanceScore 超出 1 到 10 的合法范围");
        }
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }

        Set<String> normalized = new LinkedHashSet<>();
        for (String tag : tags) {
            String value = normalize(tag);
            if (!value.isBlank()) {
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim();
    }

    /**
     * 第二阶段摘要结果
     */
    @Data
    public static class FactSummaryDTO {
        @Description("全局因果推演。在处理其他字段前，先在此简短分析输入事件的全局前因后果与核心脉络。")
        private String causalityAnalysis;

        @Description("当前最终重要事件组的高精度标签集合。")
        private List<String> tags;

        @Description("与第一阶段事件顺序一一对应的结果列表。")
        private List<FactSummaryEventDTO> events;

        @Description("对当前对话批次的 2 到 4 句总结，记忆锚点无误，因果关系正确。")
        private String summary;

        public List<FactSummaryEventDTO> payloadEvents() {
            return events == null ? List.of() : events;
        }
    }

    @Data
    public static class FactSummaryEventDTO {

        @Description("沿用原事件的 topic。")
        private String topic;

        @Description("一到两句用于检索的事件摘要，既保留主体事件，也保留核心结果。")
        private String keywordSummary;
    }

    /**
     * 第一阶段提取结果
     */
    @Data
    public static class FactExtractionDTO {
        @Description("在生成总结前，先在此简短写下你的分析：找出当前批次事件中的因果关联（什么事件导致了什么结果）。")
        private String causalityAnalysis;

        @Description("按时间发展顺序排列的事件列表。")
        private List<ExtractedFactEventDTO> events;

        public List<ExtractedFactEventDTO> payloadEvents() {
            return events == null ? List.of() : events;
        }
    }

    @Data
    public static class ExtractedFactEventDTO {
        @Description("格式为场景（如果有）+ 人物 + 核心动作。")
        private String topic;

        @Description("详细叙事，尽可能完整描述事件经过，不能有逻辑错误或遗漏实体。")
        private String narrative;

        @Description("为什么这件事值得记住，或者为什么它只应该得到当前分数。")
        private String scoreReason;

        @Description("1 到 10 分的重要性评分。")
        private int importanceScore;
    }
}
