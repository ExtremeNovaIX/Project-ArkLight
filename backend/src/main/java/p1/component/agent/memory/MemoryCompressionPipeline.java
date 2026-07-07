package p1.component.agent.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.CustomLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import p1.infrastructure.logging.LogDomain;
import p1.infrastructure.logging.LogOutcome;
import p1.component.agent.memory.model.FactExtractionPipelineResult;

import java.util.List;
import java.util.Optional;

import static p1.utils.ChatMessageUtil.isAiFinalResponseMessage;

@Service
@RequiredArgsConstructor
@CustomLog
public class MemoryCompressionPipeline {

    private static final int MIN_IMPORTANCE_SCORE = 5;

    private final FactExtractionService factExtractionService;

    public Optional<FactExtractionPipelineResult> buildPipelineResult(String sessionId, List<ChatMessage> toCompress) {
        List<ChatMessage> pureChatHistory = toCompress.stream()
                .filter(msg -> msg instanceof UserMessage || isAiFinalResponseMessage(msg))
                .toList();

        List<FactExtractionService.ExtractedFactEventDTO> extractedEvents =
                factExtractionService.extractFact(pureChatHistory, sessionId);
        log.info(LogDomain.MEMORY, "memory.compress.facts_extracted", LogOutcome.SUCCEEDED, "sessionId", sessionId, "eventCount", extractedEvents.size());

        List<FactExtractionService.ExtractedFactEventDTO> importantEvents = extractedEvents.stream()
                .filter(event -> keepEvent(sessionId, event))
                .toList();
        if (importantEvents.isEmpty()) {
            log.info(LogDomain.MEMORY, "memory.compress.no_important_events", LogOutcome.SKIPPED, "sessionId", sessionId);
            return Optional.empty();
        }

        FactExtractionService.FactSummaryDTO summary = factExtractionService.summarizeFacts(importantEvents, sessionId);
        FactExtractionPipelineResult extractionResult =
                factExtractionService.buildPipelineResult(importantEvents, summary);
        log.info(LogDomain.MEMORY, "memory.compress.summary_completed", LogOutcome.SUCCEEDED, "sessionId", sessionId, "eventCount", extractionResult.events().size(), "tagCount", extractionResult.tags().size());
        return Optional.of(extractionResult);
    }

    private boolean keepEvent(String sessionId, FactExtractionService.ExtractedFactEventDTO event) {
        if (event == null) {
            return false;
        }
        if (event.getImportanceScore() >= MIN_IMPORTANCE_SCORE) {
            return true;
        }

        log.info(LogDomain.MEMORY, "memory.compress.event_dropped", LogOutcome.SUCCEEDED, "sessionId", sessionId, "topic", event.getTopic(), "importanceScore", event.getImportanceScore());
        return false;
    }
}
