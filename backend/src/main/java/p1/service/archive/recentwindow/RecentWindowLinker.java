package p1.service.archive.recentwindow;

import lombok.CustomLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import p1.infrastructure.logging.LogDomain;
import p1.infrastructure.logging.LogOutcome;
import p1.config.prop.AssistantProperties;
import p1.model.document.MemoryArchiveDocument;
import p1.service.archive.graph.ArchiveGraphRelations;
import p1.service.archive.graph.ArchiveLinkService;
import p1.service.markdown.RecentEventGroupMarkdownService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 在目标解析完成后，真正创建 recent-window 图边。
 */
@Service
@CustomLog
@RequiredArgsConstructor
public class RecentWindowLinker {

    private final AssistantProperties props;
    private final ArchiveLinkService archiveLinkService;
    private final RecentEventGroupMarkdownService recentEventGroupService;
    private final RecentWindowTargetSelector targetSelector;
    private final RecentWindowTraceService traceService;

    /**
     * 为当前组 root 解析并连接最佳 recent-window 目标。
     */
    public void linkRoot(String sessionId,
                         List<MemoryArchiveDocument> archives,
                         List<String> currentGroupTags) {
        if (archives == null || archives.isEmpty()) {
            return;
        }

        MemoryArchiveDocument rootArchive = archives.getFirst();
        if (rootArchive == null || rootArchive.getId() == null) {
            return;
        }

        TargetSelectResult result = targetSelector.selectTarget(sessionId, archives, currentGroupTags);
        LinkTarget target = result.target();
        traceService.writeTrace(rootArchive.getId(), result.trace());
        traceService.logTrace(sessionId, rootArchive.getId(), result.trace());

        double finalThreshold = props.getEventTree().getRecentWindowFinalThreshold();
        if (target == null || target.boostedSupportScore() < finalThreshold) {
            log.info(LogDomain.MEMORY, "recent_window.link_selected", LogOutcome.SUCCEEDED, "supportScore", target == null ? 0 : target.boostedSupportScore(), "threshold", finalThreshold);
            return;
        }

        archiveLinkService.addLink(
                sessionId,
                rootArchive.getId(),
                target.targetArchiveId(),
                ArchiveGraphRelations.RECENT_WINDOW_TARGETS,
                ArchiveGraphRelations.RECENT_WINDOW_TARGETED_BY,
                target.bestScore(),
                traceService.buildLinkReason(rootArchive.getId(), target.targetArchiveId(), target.groupId())
        );
        if (!normalize(target.groupId()).isBlank()) {
            recentEventGroupService.touch(sessionId, target.groupId(), LocalDateTime.now());
        }

        log.debug("[recent-window] sessionId={} rootArchiveId={} winnerArchiveId={} winnerGroupId={} bestScore={} vectorSupportScore={} boostedSupportScore={} sharedTagCount={} sharedTags={} hitCount={} candidateCount={} usableMatchCount={}",
                sessionId,
                rootArchive.getId(),
                target.targetArchiveId(),
                target.groupId(),
                formatDouble(target.bestScore()),
                formatDouble(target.vectorSupportScore()),
                formatDouble(target.boostedSupportScore()),
                target.sharedTagCount(),
                target.sharedTags(),
                target.hitCount(),
                result.trace().candidateCount(),
                result.trace().usableMatchCount());
        log.debug("[recent-window] sessionId={} rootArchiveId={} winnerArchiveId={} winnerGroupId={} timeDecayFactor={} candidateAgeHours={} timeAdjustedSupportScore={} normalizedIdfScore={} boostedSupportScore={}",
                sessionId,
                rootArchive.getId(),
                target.targetArchiveId(),
                target.groupId(),
                formatDouble(target.timeDecayFactor()),
                target.candidateAgeHours() == null ? "-" : formatDouble(target.candidateAgeHours()),
                formatDouble(target.timeAdjustedSupportScore()),
                formatDouble(target.normalizedIdfScore()),
                formatDouble(target.boostedSupportScore()));
    }

    private String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim();
    }
}
