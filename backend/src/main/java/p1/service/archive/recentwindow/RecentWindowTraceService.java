package p1.service.archive.recentwindow;

import lombok.CustomLog;
import org.springframework.stereotype.Service;
import p1.infrastructure.logging.LogDomain;
import p1.infrastructure.logging.LogOutcome;
import p1.service.markdown.MemoryArchiveStore;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 负责 recent-window trace 的持久化和日志输出。
 */
@Service
@CustomLog
public class RecentWindowTraceService {

    private static final int LOG_MATCH_PREVIEW_LIMIT = 3;
    private static final int LOG_CANDIDATE_PREVIEW_LIMIT = 3;
    private static final int MARKDOWN_MATCH_PREVIEW_LIMIT = 5;
    private static final int MARKDOWN_CANDIDATE_PREVIEW_LIMIT = 5;

    private final MemoryArchiveStore archiveStore;

    public RecentWindowTraceService(MemoryArchiveStore archiveStore) {
        this.archiveStore = archiveStore;
    }

    /**
     * 将一次 recent-window 评分 trace 写回 root archive。
     */
    public void writeTrace(Long rootArchiveId, ScoreTrace trace) {
        if (rootArchiveId == null || trace == null) {
            return;
        }

        archiveStore.findById(rootArchiveId).ifPresent(rootArchive -> {
            rootArchive.setEventGraphTrace(toMarkdown(trace));
            archiveStore.save(rootArchive);
        });
    }

    /**
     * 输出一次 recent-window 解析过程中的 query 和 candidate 日志。
     */
    public void logTrace(String sessionId, Long rootArchiveId, ScoreTrace trace) {
        if (trace == null) {
            return;
        }

        for (QueryTrace queryTrace : trace.queries()) {
            logQuery(sessionId, rootArchiveId, queryTrace);
        }
        logCandidates(sessionId, rootArchiveId, trace);
    }

    /**
     * 生成最终落边时写入 link reason 的说明文本。
     */
    public String buildLinkReason(Long rootArchiveId, Long targetArchiveId, String targetGroupId) {
        String normalizedTargetGroupId = normalize(targetGroupId);
        return "recent-24h 主动命中: "
                + rootArchiveId
                + " -> "
                + targetArchiveId
                + (normalizedTargetGroupId.isBlank() ? "" : " (目标组ID=" + normalizedTargetGroupId + ")");
    }

    /**
     * 将评分过程渲染成便于排查的 Markdown 文本。
     */
    private String toMarkdown(ScoreTrace trace) {
        StringBuilder body = new StringBuilder();
        body.append("- 根节点ID: ").append(trace.rootArchiveId()).append("\n");
        body.append("- 当前组ID: ").append(normalize(trace.currentGroupId())).append("\n");
        body.append("- 当前标签数: ").append(trace.currentTagCount()).append("\n");
        body.append("- 当前标签: ").append(trace.currentGroupTags()).append("\n");
        body.append("- 带标签recent组数: ").append(trace.taggedGroupCount()).append("\n");
        body.append("- 可用命中数: ").append(trace.usableMatchCount()).append("\n");
        body.append("- 候选数: ").append(trace.candidateCount()).append("\n");
        body.append("- 胜出目标: ");
        LinkTarget winner = trace.winner();
        if (winner == null) {
            body.append("无\n\n");
        } else {
            body.append(label(winner.groupId(), winner.targetArchiveId()))
                    .append(" (最佳原始向量分=").append(formatDouble(winner.bestScore()))
                    .append(", 时间衰减系数=").append(formatDouble(winner.timeDecayFactor()))
                    .append(", 候选年龄(小时)=").append(winner.candidateAgeHours() == null ? "-" : formatDouble(winner.candidateAgeHours()))
                    .append(", 标签区分度=").append(formatDouble(winner.normalizedIdfScore()))
                    .append(", 最终排序分=").append(formatDouble(winner.boostedSupportScore()))
                    .append(", 共享标签=").append(winner.sharedTags())
                    .append(", 锚点命中标签=").append(winner.anchorMatchedTags())
                    .append(")\n\n");
        }

        body.append("### Query 命中\n\n");
        if (trace.queries().isEmpty()) {
            body.append("- 无\n\n");
        } else {
            for (QueryTrace query : trace.queries()) {
                body.append("- 查询#").append(query.queryIndex() + 1)
                        .append(" archiveId=").append(query.queryArchiveId())
                        .append(" weight=").append(formatDouble(query.weight()));
                if (query.skippedReason() != null) {
                    body.append(" 跳过原因=").append(query.skippedReason()).append("\n");
                    continue;
                }

                body.append(" 文本=`").append(preview(query.queryText(), 120)).append("`")
                        .append(" 命中数=").append(query.matches().size())
                        .append("\n");
                List<MatchTrace> markdownMatches = query.matches().stream()
                        .limit(MARKDOWN_MATCH_PREVIEW_LIMIT)
                        .toList();
                if (markdownMatches.isEmpty()) {
                    body.append("  - 无\n");
                    continue;
                }
                for (MatchTrace match : markdownMatches) {
                    body.append("  - 目标=")
                            .append(label(match.targetGroupId(), match.targetArchiveId()))
                            .append(", 分数=").append(formatDouble(match.score()))
                            .append(", 组内顺序=")
                            .append(match.groupOrder() == null ? "-" : match.groupOrder())
                            .append(", keywordSummary=`")
                            .append(preview(match.keywordSummary(), 120))
                            .append("`\n");
                }
            }
            body.append("\n");
        }

        body.append("### Candidate 聚合\n\n");
        if (trace.candidates().isEmpty()) {
            body.append("- 无\n");
        } else {
            for (CandidateTrace candidate : trace.candidates().stream().limit(MARKDOWN_CANDIDATE_PREVIEW_LIMIT).toList()) {
                body.append("- 候选=")
                        .append(label(candidate.groupId(), candidate.targetArchiveId()))
                        .append(", 向量支持度=").append(formatDouble(candidate.vectorSupportScore()))
                        .append(", 时间调整后支持度=").append(formatDouble(candidate.timeAdjustedSupportScore()))
                        .append(", 时间衰减系数=").append(formatDouble(candidate.timeDecayFactor()))
                        .append(", 候选年龄(小时)=").append(candidate.candidateAgeHours() == null ? "-" : formatDouble(candidate.candidateAgeHours()))
                        .append(", 标签区分度=").append(formatDouble(candidate.normalizedIdfScore()))
                        .append(", 最终排序分=").append(formatDouble(candidate.boostedSupportScore()))
                        .append(", 最佳原始向量分=").append(formatDouble(candidate.bestScore()))
                        .append(", 命中数=").append(candidate.hitCount())
                        .append(", 共享标签数=").append(candidate.sharedTags().size())
                        .append(", 共享标签=").append(candidate.sharedTags())
                        .append(", 锚点命中标签=").append(candidate.anchorMatchedTags())
                        .append("\n");
            }
        }

        return body.toString().trim();
    }

    private void logQuery(String sessionId, Long rootArchiveId, QueryTrace queryTrace) {
        if (queryTrace.skippedReason() != null) {
            log.info(LogDomain.MEMORY, "recent_window.query_skipped", LogOutcome.SKIPPED, "sessionId", sessionId, "rootArchiveId", rootArchiveId, "queryArchiveId", queryTrace.queryArchiveId(), "weight", formatDouble(queryTrace.weight()), "skippedReason", queryTrace.skippedReason());
            return;
        }

        String preview = queryTrace.matches().isEmpty()
                ? "无"
                : queryTrace.matches().stream()
                .limit(LOG_MATCH_PREVIEW_LIMIT)
                .map(this::matchPreview)
                .collect(Collectors.joining(" | "));

        log.info(LogDomain.MEMORY, "recent_window.query_matched", LogOutcome.SUCCEEDED, "sessionId", sessionId, "rootArchiveId", rootArchiveId, "queryArchiveId", queryTrace.queryArchiveId(), "weight", formatDouble(queryTrace.weight()), "matchCount", queryTrace.matches().size(), "queryPreview", preview(queryTrace.queryText(), 60), "preview", preview);
    }

    private void logCandidates(String sessionId, Long rootArchiveId, ScoreTrace trace) {
        String preview = trace.candidates().isEmpty()
                ? "无"
                : trace.candidates().stream()
                .limit(LOG_CANDIDATE_PREVIEW_LIMIT)
                .map(this::candidatePreview)
                .collect(Collectors.joining(" | "));

        log.info(LogDomain.MEMORY, "recent_window.trace_recorded", LogOutcome.SUCCEEDED, "sessionId", sessionId, "rootArchiveId", rootArchiveId, "currentTagCount", trace.currentTagCount(), "taggedGroupCount", trace.taggedGroupCount(), "usableMatchCount", trace.usableMatchCount(), "candidateCount", trace.candidateCount(), "preview", preview);
    }

    private String matchPreview(MatchTrace match) {
        return label(match.targetGroupId(), match.targetArchiveId())
                + "@score=" + formatDouble(match.score())
                + "@order=" + (match.groupOrder() == null ? "-" : match.groupOrder());
    }

    private String candidatePreview(CandidateTrace candidate) {
        return label(candidate.groupId(), candidate.targetArchiveId())
                + "@boost=" + formatDouble(candidate.boostedSupportScore())
                + "@vec=" + formatDouble(candidate.vectorSupportScore())
                + "@decay=" + formatDouble(candidate.timeDecayFactor())
                + "@ageH=" + (candidate.candidateAgeHours() == null ? "-" : formatDouble(candidate.candidateAgeHours()))
                + "@idf=" + formatDouble(candidate.normalizedIdfScore())
                + "@hits=" + candidate.hitCount()
                + "@sharedTags=" + candidate.sharedTags().size()
                + "@anchorTags=" + candidate.anchorMatchedTags().size();
    }

    private String label(String groupId, Long targetArchiveId) {
        String normalizedGroupId = normalize(groupId);
        if (!normalizedGroupId.isBlank()) {
            return normalizedGroupId + "#" + targetArchiveId;
        }
        return "archive#" + targetArchiveId;
    }

    private String preview(String text, int maxLength) {
        String normalized = normalize(text);
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength - 3) + "...";
    }

    private String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim();
    }
}
