package p1.infrastructure.markdown.assembler;

import org.springframework.stereotype.Component;
import p1.infrastructure.markdown.core.FrontmatterBuilder;
import p1.infrastructure.markdown.core.MarkdownBodyBuilder;
import p1.infrastructure.markdown.model.MarkdownDocument;
import p1.model.enums.MessageRole;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Component
public class RawMdAssembler {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    public MarkdownDocument createDailyNote(String sessionId, LocalDate date) {
        String dateText = DATE_FORMATTER.format(date);
        Map<String, Object> frontmatter = new FrontmatterBuilder()
                .put("id", "raw-dialogue-" + sessionId + "-" + dateText)
                .put("type", "raw_dialogue_day")
                .put("session_id", sessionId)
                .put("date", dateText)
                .put("llm_access", "read_only")
                .put("tags", List.of("raw/dialogue", "daily"))
                .toMap();

        String body = new MarkdownBodyBuilder()
                .title("Dialogue " + dateText)
                .paragraph("> System-owned raw dialogue log.")
                .build();
        return new MarkdownDocument(frontmatter, body);
    }

    public String buildMessageBlock(MessageRole role,
                                    String cleanText,
                                    LocalDateTime timestamp,
                                    String messageId) {
        return "\n## "
                + TIME_FORMATTER.format(timestamp)
                + " "
                + role.name()
                + "\n"
                + cleanText
                + "\n^"
                + messageId
                + "\n";
    }
}
