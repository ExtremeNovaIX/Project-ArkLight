package p1.infrastructure.markdown.assembler;

import lombok.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import p1.infrastructure.markdown.codec.ArchiveLinkCodec;
import p1.infrastructure.markdown.core.FrontmatterBuilder;
import p1.infrastructure.markdown.core.FrontmatterReader;
import p1.infrastructure.markdown.core.MarkdownBodyBuilder;
import p1.infrastructure.markdown.core.MarkdownSections;
import p1.infrastructure.markdown.model.MarkdownDocument;
import p1.model.document.MemoryArchiveDocument;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class MemoryArchiveMdAssembler {
    private static final String RETRIEVAL_SUMMARY_HEADER = "## Retrieval Summary";
    private static final String LINKS_HEADER = "## Links";
    private static final String EVENT_GRAPH_TRACE_HEADER = "## Event Graph Trace";

    public MemoryArchiveDocument fromMarkdown(@NonNull MarkdownDocument document) {
        FrontmatterReader frontmatter = FrontmatterReader.of(document.frontmatter());
        MarkdownSections sections = MarkdownSections.parse(document.body(), List.of(
                RETRIEVAL_SUMMARY_HEADER,
                LINKS_HEADER,
                EVENT_GRAPH_TRACE_HEADER
        ));

        MemoryArchiveDocument archive = new MemoryArchiveDocument();
        archive.setId(archiveIdValue(frontmatter.string("id")));
        archive.setSessionId(frontmatter.string("session_id"));
        archive.setGroupId(frontmatter.string("group_id"));
        archive.setGroupOrder(frontmatter.intValue("group_order"));
        archive.setTopic(frontmatter.string("topic"));
        archive.setKeywordSummary(sections.content(RETRIEVAL_SUMMARY_HEADER));
        archive.setNarrative(sections.preamble());
        archive.setEventGraph("");
        archive.setEventGraphTrace(sections.content(EVENT_GRAPH_TRACE_HEADER));
        archive.setCreatedAt(frontmatter.dateTime("created_at"));
        archive.setUpdatedAt(frontmatter.dateTime("updated_at"));
        archive.setSourceRefs(frontmatter.stringList("source_refs"));
        archive.setGroupTags(resolveGroupTags(frontmatter));
        archive.setLinks(ArchiveLinkCodec.fromFrontmatter(frontmatter.get("links")));
        return archive;
    }

    public MarkdownDocument toMarkdown(@NonNull MemoryArchiveDocument archive) {
        return toMarkdown(archive, List.of());
    }

    public MarkdownDocument toMarkdown(@NonNull MemoryArchiveDocument archive, List<RenderedArchiveLink> renderedLinks) {
        String displayTitle = buildTitle(archive);
        Map<String, Object> frontmatter = new FrontmatterBuilder()
                .put("id", noteId(archive.getId()))
                .put("type", "memory_archive")
                .put("session_id", archive.getSessionId())
                .putNormalized("group_id", archive.getGroupId())
                .put("group_order", archive.getGroupOrder())
                .putNormalized("topic", archive.getTopic())
                .putDateTime("created_at", archive.getCreatedAt())
                .putDateTime("updated_at", archive.getUpdatedAt())
                .put("source_refs", archive.getSourceRefs() == null ? List.of() : archive.getSourceRefs())
                .put("group_tags", archive.getGroupTags() == null ? List.of() : archive.getGroupTags())
                .put("links", ArchiveLinkCodec.toFrontmatter(archive.getLinks()))
                .put("tags", buildTags(archive))
                .toMap();

        MarkdownBodyBuilder body = new MarkdownBodyBuilder()
                .title(displayTitle)
                .paragraph(archive.getNarrative())
                .section(RETRIEVAL_SUMMARY_HEADER, archive.getKeywordSummary());

        List<RenderedArchiveLink> bodyLinks = renderedLinks == null
                ? List.of()
                : renderedLinks.stream()
                .filter(link -> !isTimelineRelation(link == null ? null : link.relation()))
                .toList();

        if (!bodyLinks.isEmpty()) {
            body.sectionHeader(LINKS_HEADER);
            for (RenderedArchiveLink link : bodyLinks) {
                String reason = link.reason() == null ? "" : link.reason().trim();
                String relation = link.relation() == null ? "" : link.relation().trim();
                String line = "- " + relation + ": " + link.wikilink();
                if (!reason.isBlank()) {
                    line += " - " + reason;
                }
                body.line(line);
            }
            body.blankLine();
        }

        body.section(EVENT_GRAPH_TRACE_HEADER, archive.getEventGraphTrace());
        return new MarkdownDocument(frontmatter, body.build());
    }

    public String noteId(Long id) {
        return String.format("memory-%05d", id);
    }

    public String buildTitle(@NonNull MemoryArchiveDocument archive) {
        String topic = archive.getTopic();
        return StringUtils.hasText(topic) ? topic.trim() : noteId(archive.getId());
    }

    private Long archiveIdValue(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String text = value.trim();
        int lastDashIndex = text.lastIndexOf('-');
        if (lastDashIndex >= 0 && lastDashIndex + 1 < text.length()) {
            text = text.substring(lastDashIndex + 1);
        }
        return Long.parseLong(text);
    }

    private boolean isTimelineRelation(String relation) {
        if (!StringUtils.hasText(relation)) {
            return false;
        }
        String trimmedRelation = relation.trim();
        return "next_in_time".equals(trimmedRelation)
                || "previous_in_time".equals(trimmedRelation);
    }

    private List<String> buildTags(@NonNull MemoryArchiveDocument archive) {
        Set<String> tags = new LinkedHashSet<>();
        String groupId = archive.getGroupId();
        if (StringUtils.hasText(groupId)) {
            tags.add(groupId.trim());
        }

        List<String> groupTags = archive.getGroupTags();
        if (groupTags == null || groupTags.isEmpty()) {
            return List.copyOf(tags);
        }

        String narrative = archive.getNarrative();
        if (!StringUtils.hasText(narrative)) {
            return List.copyOf(tags);
        }

        String trimmedNarrative = narrative.trim();
        for (String groupTag : groupTags) {
            if (!StringUtils.hasText(groupTag)) {
                continue;
            }
            String trimmedGroupTag = groupTag.trim();
            if (trimmedNarrative.contains(trimmedGroupTag)) {
                tags.add(trimmedGroupTag);
            }
        }
        return List.copyOf(tags);
    }

    /**
     * 从 frontmatter 中解析 group_tags。
     */
    private List<String> resolveGroupTags(@NonNull FrontmatterReader frontmatter) {
        List<String> explicitGroupTags = frontmatter.stringList("group_tags");
        if (!explicitGroupTags.isEmpty()) {
            return explicitGroupTags;
        }

        String groupId = frontmatter.string("group_id");
        String trimmedGroupId = StringUtils.hasText(groupId) ? groupId.trim() : "";
        return frontmatter.stringList("tags").stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .filter(tag -> !tag.equals(trimmedGroupId))
                .distinct()
                .toList();
    }

    public record RenderedArchiveLink(String relation, String wikilink, String reason) {
    }
}
