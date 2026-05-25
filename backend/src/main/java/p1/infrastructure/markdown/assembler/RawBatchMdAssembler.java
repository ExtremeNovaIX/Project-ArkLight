package p1.infrastructure.markdown.assembler;

import org.springframework.stereotype.Component;
import p1.infrastructure.markdown.core.FrontmatterBuilder;
import p1.infrastructure.markdown.core.FrontmatterReader;
import p1.infrastructure.markdown.core.MarkdownBodyBuilder;
import p1.infrastructure.markdown.model.DialogueBatchMessage;
import p1.infrastructure.markdown.model.MarkdownDocument;
import p1.infrastructure.markdown.model.RawBatchDocument;
import p1.model.enums.MessageRole;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RawBatchMdAssembler {

    public RawBatchDocument fromMarkdown(MarkdownDocument markdownDocument) {
        FrontmatterReader frontmatter = FrontmatterReader.of(markdownDocument.frontmatter());
        return new RawBatchDocument(
                frontmatter.string("id"),
                frontmatter.string("session_id"),
                frontmatter.string("status"),
                frontmatter.dateTime("created_at"),
                frontmatter.dateTime("updated_at"),
                frontmatter.dateTime("processing_started_at"),
                messageList(frontmatter.get("messages"))
        );
    }

    public MarkdownDocument toMarkdown(RawBatchDocument document) {
        FrontmatterBuilder frontmatter = new FrontmatterBuilder()
                .put("id", document.id())
                .put("type", "dialogue_batch")
                .put("session_id", document.sessionId())
                .put("status", document.status())
                .putDateTime("created_at", document.createdAt())
                .putDateTime("updated_at", document.updatedAt());
        if (document.processingStartedAt() != null) {
            frontmatter.putDateTime("processing_started_at", document.processingStartedAt());
        }
        List<DialogueBatchMessage> messages = document.messages() == null ? List.of() : document.messages();
        frontmatter.put("message_count", document.messageCount())
                .put("messages", messages.stream().map(this::toMap).toList())
                .put("tags", List.of("system/dialogue-batch"));

        String body = new MarkdownBodyBuilder()
                .title("Dialogue Batch " + document.id())
                .paragraph("System-owned transient batch state for session `" + document.sessionId() + "`.")
                .build();
        return new MarkdownDocument(frontmatter.toMap(), body);
    }

    private Map<String, Object> toMap(DialogueBatchMessage message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("message_id", message.messageId());
        map.put("role", message.role().name());
        map.put("text", message.text());
        map.put("created_at", message.createdAt() == null ? null : message.createdAt().toString());
        map.put("raw_note_path", message.rawNotePath());
        map.put("raw_message_id", message.rawMessageId());
        return map;
    }

    private List<DialogueBatchMessage> messageList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }

        List<DialogueBatchMessage> messages = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            FrontmatterReader reader = FrontmatterReader.of(map);
            messages.add(new DialogueBatchMessage(
                    reader.string("message_id"),
                    MessageRole.valueOf(reader.string("role")),
                    reader.rawString("text"),
                    reader.dateTime("created_at"),
                    reader.string("raw_note_path"),
                    reader.string("raw_message_id")
            ));
        }
        return messages;
    }
}
