package p1.benchmark.halumem;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import p1.component.agent.memory.MemoryCompressionPipeline;
import p1.component.agent.memory.MemoryWriteService;
import p1.component.agent.memory.model.FactExtractionPipelineResult;
import p1.component.agent.rp.context.SummaryCacheManager;
import p1.model.document.MemoryArchiveDocument;
import p1.service.markdown.MemoryArchiveStore;
import p1.utils.SessionUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class HaluMemIngestionService {

    private final MemoryCompressionPipeline memoryCompressionPipeline;
    private final MemoryWriteService memoryWriteService;
    private final SummaryCacheManager summaryCacheManager;
    private final MemoryArchiveStore archiveStore;

    public HaluMemSessionIngestResponse ingest(HaluMemSessionIngestRequest request) {
        String sessionId = SessionUtil.normalizeSessionId(request.sessionId());
        String sessionLabel = normalize(request.sessionLabel());
        List<ChatMessage> dialogue = normalizeDialogue(request.dialogue());
        FactExtractionPipelineResult extractionResult =
                memoryCompressionPipeline.buildPipelineResult(sessionId, dialogue).orElse(null);

        List<MemoryArchiveDocument> persistedArchives = List.of();
        List<HaluMemMemoryItem> extractedMemories = List.of();
        List<String> tags = List.of();
        String summary = "";

        if (extractionResult != null) {
            tags = extractionResult.tags() == null ? List.of() : extractionResult.tags();
            summary = normalize(extractionResult.summary());
            extractedMemories = HaluMemRenderSupport.fromExtractedEvents(extractionResult.events());
            persistedArchives = memoryWriteService.saveEventGroup(
                    sessionId,
                    extractionResult.events(),
                    tags,
                    buildSourceRefs(sessionLabel, request.sourceRefs())
            );
            if (!summary.isBlank()) {
                summaryCacheManager.updateSummary(sessionId, summary);
            }
        }

        List<HaluMemMemoryItem> currentMemories = HaluMemRenderSupport.fromArchives(archiveStore.findAllOrderByIdAsc(sessionId));
        return new HaluMemSessionIngestResponse(
                sessionId,
                sessionLabel,
                dialogue.size(),
                extractedMemories.size(),
                persistedArchives.size(),
                List.copyOf(tags),
                summary,
                extractedMemories,
                currentMemories
        );
    }

    private List<ChatMessage> normalizeDialogue(List<HaluMemMessageDTO> dialogue) {
        if (dialogue == null || dialogue.isEmpty()) {
            return List.of();
        }

        List<ChatMessage> messages = new ArrayList<>();
        for (HaluMemMessageDTO message : dialogue) {
            if (message == null || !StringUtils.hasText(message.content())) {
                continue;
            }
            String role = normalizeRole(message.role());
            String content = message.content().trim();
            if ("user".equals(role)) {
                messages.add(UserMessage.from(content));
            } else if ("assistant".equals(role)) {
                messages.add(AiMessage.from(content));
            }
        }
        return List.copyOf(messages);
    }

    private List<String> buildSourceRefs(String sessionLabel, List<String> sourceRefs) {
        Set<String> refs = new LinkedHashSet<>();
        refs.add("benchmark:halumem");
        if (!sessionLabel.isBlank()) {
            refs.add("halumem_session:" + sessionLabel);
        }
        if (sourceRefs != null) {
            for (String sourceRef : sourceRefs) {
                String normalized = normalize(sourceRef);
                if (!normalized.isBlank()) {
                    refs.add(normalized);
                }
            }
        }
        return List.copyOf(refs);
    }

    private String normalizeRole(String role) {
        return normalize(role).toLowerCase(java.util.Locale.ROOT);
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim();
    }
}
