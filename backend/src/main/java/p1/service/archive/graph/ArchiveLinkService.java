package p1.service.archive.graph;

import lombok.CustomLog;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import p1.infrastructure.logging.LogDomain;
import p1.infrastructure.logging.LogOutcome;
import p1.component.agent.memory.model.ArchiveLink;
import p1.model.document.MemoryArchiveDocument;
import p1.service.markdown.MemoryArchiveStore;
import p1.service.markdown.RecentEventGroupMarkdownService;
import p1.utils.SessionUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@CustomLog
public class ArchiveLinkService {

    private final MemoryArchiveStore archiveStore;
    private final RecentEventGroupMarkdownService recentEventGroupService;


    /**
     * 为源事件添加一条出边。
     * 如果同一 relation + targetArchiveId 的边已经存在，则只更新说明和置信度，不重复追加。
     */
    public MemoryArchiveDocument addOutgoingLink(MemoryArchiveDocument sourceArchive,
                                                 Long targetArchiveId,
                                                 String relation,
                                                 Double confidence,
                                                 String reason) {
        if (!hasPersistedId(sourceArchive) || targetArchiveId == null) {
            return sourceArchive;
        }
        if (sourceArchive.getId().equals(targetArchiveId)) {
            return sourceArchive;
        }

        // 先读一遍当前最新版本，避免在旧对象上追加 link 导致覆盖掉别的更新。
        // 先回读最新 archive，再在最新版本上 merge，避免覆盖掉并发写入的其他边。
        MemoryArchiveDocument latestSource = archiveStore.findById(sourceArchive.getId()).orElse(sourceArchive);
        String targetTopic = archiveStore.findById(targetArchiveId)
                .map(MemoryArchiveDocument::getTopic)
                .orElse("");
        boolean changed = mergeLink(latestSource, targetArchiveId, targetTopic, relation, confidence, reason);
        if (!changed) {
            return latestSource;
        }

        MemoryArchiveDocument saved = archiveStore.save(latestSource);
        log.info(LogDomain.MEMORY, "archive.link_created", LogOutcome.SUCCEEDED, "archiveId", saved.getId(), "targetArchiveId", targetArchiveId, "relation", normalize(relation));
        return saved;
    }

    /**
     * 在两个 archive 之间建立双向边。
     * 这是第三阶段最基础的图操作：组内天然链和 recent-24h 命中后的跨组边都会走这里。
     */
    public void addLink(String sessionId,
                        Long leftArchiveId,
                        Long rightArchiveId,
                        String leftToRightRelation,
                        String rightToLeftRelation,
                        Double confidence,
                        String reason) {
        if (leftArchiveId == null || rightArchiveId == null || leftArchiveId.equals(rightArchiveId)) {
            return;
        }

        String normalizedSessionId = SessionUtil.normalizeSessionId(sessionId);
        MemoryArchiveDocument leftArchive = archiveStore.findById(leftArchiveId).orElse(null);
        MemoryArchiveDocument rightArchive = archiveStore.findById(rightArchiveId).orElse(null);

        if (leftArchive == null || rightArchive == null) {
            log.warn(LogDomain.MEMORY, "archive.link_skipped", LogOutcome.DEGRADED, "sessionId", normalizedSessionId, "leftArchiveId", leftArchiveId, "rightArchiveId", rightArchiveId);
            return;
        }

        // 两边都调用 mergeLink，这样重复执行时会变成更新，不会无限叠加重复边。
        mergeLink(leftArchive, rightArchiveId, rightArchive.getTopic(), leftToRightRelation, confidence, reason);
        mergeLink(rightArchive, leftArchiveId, leftArchive.getTopic(), rightToLeftRelation, confidence, reason);
        archiveStore.save(leftArchive);
        archiveStore.save(rightArchive);

        log.debug("[Archive 连边] 已建立双向边，sessionId={}，leftArchiveId={}，rightArchiveId={}，leftRelation={}，rightRelation={}",
                normalizedSessionId, leftArchiveId, rightArchiveId, normalize(leftToRightRelation), normalize(rightToLeftRelation));
    }

    /**
     * 这个方法会做时间线的链接：
     * <p>
     * 1.在同一组事件内部建立时间双向链。
     * 相邻事件之间会形成：
     * - current -> next : next_in_time
     * - next -> current : previous_in_time
     * <p>
     * 2.如果存在上一批事件组，则把当前根节点的时间连接到上一批的尾节点，形成完整的时间链条结构。
     */
    public void linkGroupTimeLine(String sessionId, List<MemoryArchiveDocument> archives) {
        if (archives == null || archives.size() < 2) {
            return;
        }

        // 在同一组事件内部的相邻事件之间建立时间双向链
        for (int index = 0; index < archives.size() - 1; index++) {
            MemoryArchiveDocument current = archives.get(index);
            MemoryArchiveDocument next = archives.get(index + 1);
            if (!hasPersistedId(current) || !hasPersistedId(next)) {
                continue;
            }

            addLink(
                    sessionId,
                    current.getId(),
                    next.getId(),
                    "next_in_time",
                    "previous_in_time",
                    1.0,
                    "同一提取组内的时间顺序"
            );
        }
        linkCurrentRootToPreviousTail(sessionId, archives);
    }

    /**
     * 如果存在上一批事件组，则把当前根节点的时间连接到上一批的尾节点，形成完整的时间链条结构。
     */
    public void linkCurrentRootToPreviousTail(String sessionId, List<MemoryArchiveDocument> archives) {
        if (archives == null || archives.isEmpty()) {
            return;
        }

        MemoryArchiveDocument rootArchive = archives.getFirst();
        if (!hasPersistedId(rootArchive)) {
            return;
        }

        recentEventGroupService.findLatestBySessionId(sessionId).ifPresent(previousGroup -> {
            Long previousTailArchiveId = previousGroup.getTailArchiveId();
            if (previousTailArchiveId == null || previousTailArchiveId.equals(rootArchive.getId())) {
                return;
            }

            addLink(
                    sessionId,
                    previousTailArchiveId,
                    rootArchive.getId(),
                    "next_in_time",
                    "previous_in_time",
                    1.0,
                    "新链条接到上一批的最后一个时间节点"
            );

            log.debug("[时间边连接] sessionId={} 当前组根节点与上一批尾节点的时间边已连接，previousTailArchiveId={}，currentRootArchiveId={}，previousGroupId={}",
                    sessionId, previousTailArchiveId, rootArchive.getId(), previousGroup.getId());
        });
    }

    /**
     * 合并单条 link，防止重复添加。
     * 这里的“merge”不是图层面的合并，而是对单个 archive.links 列表做一次 upsert：
     * - 同一个 target + relation，视为同一条边，只在说明或置信度变化时才更新
     * - 否则新增一条边
     */
    private boolean mergeLink(@NonNull MemoryArchiveDocument sourceArchive,
                              @NonNull Long targetArchiveId,
                              String targetTopic,
                              String relation,
                              Double confidence,
                              String reason) {
        if (sourceArchive.getLinks() == null) {
            sourceArchive.setLinks(new ArrayList<>());
        }
        sourceArchive.getLinks().removeIf(Objects::isNull);

        String normalizedRelation = normalize(relation);
        String normalizedTargetTopic = normalize(targetTopic);
        String normalizedReason = normalize(reason);
        for (ArchiveLink existingLink : sourceArchive.getLinks()) {
            // 同一个 target + relation 视为同一条边，只有边内容真的变化时才标记为 changed。
            if (targetArchiveId.equals(existingLink.getTargetArchiveId())
                    && normalizedRelation.equals(normalize(existingLink.getRelation()))) {
                boolean changed = !equalsNullable(existingLink.getConfidence(), confidence)
                        || !normalize(existingLink.getTargetTopic()).equals(normalizedTargetTopic)
                        || !normalize(existingLink.getReason()).equals(normalizedReason);
                if (!changed) {
                    return false;
                }
                existingLink.setTargetTopic(normalizedTargetTopic);
                existingLink.setConfidence(confidence);
                existingLink.setReason(normalizedReason);
                return true;
            }
        }

        // 只有列表里确实没有同类边时，才追加一条新的 link。
        sourceArchive.getLinks().add(new ArchiveLink(
                normalizedRelation,
                targetArchiveId,
                normalizedTargetTopic,
                confidence,
                normalizedReason
        ));
        return true;
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim();
    }

    private boolean equalsNullable(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private boolean hasPersistedId(MemoryArchiveDocument archive) {
        return archive != null && archive.getId() != null;
    }
}
