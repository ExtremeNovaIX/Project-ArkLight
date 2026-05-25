package p1.service.archive.recentwindow;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import p1.model.document.MemoryArchiveDocument;
import p1.model.document.RecentEventGroupDocument;
import p1.service.markdown.RecentEventGroupMarkdownService;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 构建 recent-window 重排所需的上下文。
 */
@Service
@RequiredArgsConstructor
class RecentWindowContextBuilder {

    private final RecentEventGroupMarkdownService recentEventGroupService;

    /**
     * 收集 root 标签、组级标签频率和组创建时间，供后续重排使用。
     */
    RankContext build(String sessionId,
                      @NonNull MemoryArchiveDocument rootArchive,
                      List<String> currentGroupTags) {
        // 只保留真正出现在当前节点文本里的标签，避免组级泛标签把重排带偏。
        List<String> rootTags = selectRootTags(rootArchive, currentGroupTags);
        Set<String> currentTags = RecentWindowSupport.normalizeTags(rootTags);
        Map<String, Set<String>> tagsByGroupId = new LinkedHashMap<>();
        Map<String, Integer> documentFrequency = new LinkedHashMap<>();
        Map<String, LocalDateTime> createdAtByGroupId = new LinkedHashMap<>();
        int taggedGroupCount = 0;

        for (RecentEventGroupDocument group : recentEventGroupService.findAllBySessionId(sessionId).stream()
                .filter(Objects::nonNull)
                .toList()) {
            String groupId = RecentWindowSupport.normalize(group.getId());
            if (groupId.isBlank()) {
                continue;
            }

            if (group.getCreatedAt() != null) {
                createdAtByGroupId.put(groupId, group.getCreatedAt());
            }

            Set<String> groupTags = RecentWindowSupport.normalizeTags(group.getGroupTags());
            if (groupTags.isEmpty()) {
                continue;
            }

            taggedGroupCount++;
            tagsByGroupId.put(groupId, groupTags);

            // DF 统计的是“多少个组包含该标签”，不是命中次数。
            for (String tag : groupTags) {
                documentFrequency.merge(tag, 1, Integer::sum);
            }
        }

        return new RankContext(
                currentTags,
                tagsByGroupId,
                documentFrequency,
                taggedGroupCount,
                createdAtByGroupId,
                LocalDateTime.now()
        );
    }

    /**
     * 只挑出真正出现在 root 文本里的组标签。
     */
    private List<String> selectRootTags(@NonNull MemoryArchiveDocument rootArchive, List<String> currentGroupTags) {
        List<String> tags = currentGroupTags == null ? List.of() : currentGroupTags;
        String rootText = RecentWindowSupport.normalize(rootArchive.getKeywordSummary());

        return tags.stream()
                .filter(tag -> {
                    String normalizedTag = RecentWindowSupport.normalize(tag);
                    return !normalizedTag.isBlank() && rootText.contains(normalizedTag);
                })
                .toList();
    }
}
