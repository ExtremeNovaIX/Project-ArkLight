package p1.infrastructure.markdown.assembler;

import org.springframework.stereotype.Component;
import p1.infrastructure.markdown.codec.RecentEventGroupLinkCodec;
import p1.infrastructure.markdown.core.FrontmatterBuilder;
import p1.infrastructure.markdown.core.FrontmatterReader;
import p1.infrastructure.markdown.core.MarkdownBodyBuilder;
import p1.infrastructure.markdown.model.MarkdownDocument;
import p1.model.document.RecentEventGroupDocument;

import java.util.List;
import java.util.Map;

/**
 * 用于将Markdown文档组装为RecentEventGroupDocument或从RecentEventGroupDocument转换为Markdown文档
 * RecentEventGroupDocument: 事件组的文档，包含某次提取出的所有事件的链接记录以及tag列表
 */
@Component
public class RecentEventGroupMdAssembler {

    public RecentEventGroupDocument fromMarkdown(MarkdownDocument document) {
        FrontmatterReader frontmatter = FrontmatterReader.of(document.frontmatter());

        RecentEventGroupDocument group = new RecentEventGroupDocument();
        group.setId(frontmatter.string("id"));
        group.setSessionId(frontmatter.string("session_id"));
        group.setTopic(frontmatter.string("topic"));
        group.setCreatedAt(frontmatter.dateTime("created_at"));
        group.setUpdatedAt(frontmatter.dateTime("updated_at"));
        group.setLastHitAt(frontmatter.dateTime("last_hit_at"));
        group.setHeadArchiveId(frontmatter.longValue("head_archive_id"));
        group.setTailArchiveId(frontmatter.longValue("tail_archive_id"));
        group.setArchiveIds(frontmatter.longList("archive_ids"));
        group.setRecentVectorDocumentIds(frontmatter.stringList("recent_vector_document_ids"));
        group.setGroupTags(frontmatter.stringList("group_tags"));
        group.setLinks(RecentEventGroupLinkCodec.fromFrontmatter(frontmatter.get("links")));
        return group;
    }

    public MarkdownDocument toMarkdown(RecentEventGroupDocument group) {
        return toMarkdown(group, List.of());
    }

    public MarkdownDocument toMarkdown(RecentEventGroupDocument group, List<RenderedArchiveRef> archiveRefs) {
        Map<String, Object> frontmatter = new FrontmatterBuilder()
                .putNormalized("id", group.getId())
                .put("type", "recent_event_group")
                .putNormalized("session_id", group.getSessionId())
                .putNormalized("topic", group.getTopic())
                .putDateTime("created_at", group.getCreatedAt())
                .putDateTime("updated_at", group.getUpdatedAt())
                .putDateTime("last_hit_at", group.getLastHitAt())
                .put("head_archive_id", group.getHeadArchiveId())
                .put("tail_archive_id", group.getTailArchiveId())
                .put("archive_ids", group.getArchiveIds() == null ? List.of() : group.getArchiveIds())
                .put("recent_vector_document_ids",
                        group.getRecentVectorDocumentIds() == null ? List.of() : group.getRecentVectorDocumentIds())
                .put("group_tags", group.getGroupTags() == null ? List.of() : group.getGroupTags())
                .put("links", RecentEventGroupLinkCodec.toFrontmatter(group.getLinks()))
                .put("tags", List.of())
                .toMap();

        MarkdownBodyBuilder body = new MarkdownBodyBuilder()
                .title(group.getId())
                .line("topic: " + normalize(group.getTopic()))
                .line("head_archive_id: " + (group.getHeadArchiveId() == null ? "" : group.getHeadArchiveId()))
                .line("tail_archive_id: " + (group.getTailArchiveId() == null ? "" : group.getTailArchiveId()))
                .line("event_count: " + (group.getArchiveIds() == null ? 0 : group.getArchiveIds().size()))
                .line("group_tags: " + (group.getGroupTags() == null ? "[]" : group.getGroupTags()));

        if (archiveRefs != null && !archiveRefs.isEmpty()) {
            body.blankLine().sectionHeader("## Events");
            for (RenderedArchiveRef archiveRef : archiveRefs) {
                if (archiveRef == null || normalize(archiveRef.wikilink()).isBlank()) {
                    continue;
                }
                body.line("- " + archiveRef.wikilink());
            }
        }

        return new MarkdownDocument(frontmatter, body.build());
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim();
    }

    public record RenderedArchiveRef(String wikilink) {
    }
}
