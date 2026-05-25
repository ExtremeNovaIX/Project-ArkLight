package p1.component.agent.tools;

import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import p1.component.agent.memory.model.ArchiveLink;
import p1.infrastructure.vector.ArchiveVectorLibrary;
import p1.model.document.MemoryArchiveDocument;
import p1.model.document.RecentEventGroupDocument;
import p1.service.archive.ArchiveEmbeddingService;
import p1.service.markdown.MemoryArchiveStore;
import p1.service.markdown.RecentEventGroupMarkdownService;

import java.util.*;

import static p1.utils.SessionUtil.normalizeSessionId;

@Slf4j
@Component
@AllArgsConstructor
public class MemorySearchTools {

    private static final int MAX_VECTOR_MATCHES = 3;
    private static final double MIN_MATCH_SCORE = 0.5;
    private static final int MAX_GRAPH_EXPANSION_SIZE = 8;
    private static final String STATUS_OK = "OK";
    private static final String STATUS_EMPTY = "EMPTY";
    private static final String STATUS_INVALID_QUERY = "INVALID_QUERY";
    private static final String PRIORITY_SEED_EXTERNAL_TWO_HOP = "seed_external_two_hop";
    private static final String PRIORITY_SAME_GROUP_EXTERNAL_ONE_HOP = "same_group_external_one_hop";

    private final ArchiveEmbeddingService archiveEmbeddingService;
    private final MemoryArchiveStore archiveStore;
    private final RecentEventGroupMarkdownService recentEventGroupService;

    public MemorySearchResult searchLongTermMemory(@NonNull String query) {
        if (!StringUtils.hasText(query)) {
            return new MemorySearchResult("", STATUS_INVALID_QUERY, "记忆检索请求不能为空。", List.of(), false);
        }

        String trimmedQuery = query.trim();
        String sessionId = normalizeSessionId(MDC.get("sessionId"));
        log.info("[记忆工具] 开始检索长期记忆，sessionId={}，query={}", sessionId, trimmedQuery);

        List<ArchiveEmbeddingService.ArchiveVectorMatch> matches = archiveEmbeddingService.searchArchiveMatches(
                sessionId,
                ArchiveVectorLibrary.ARCHIVE,
                trimmedQuery,
                MAX_VECTOR_MATCHES,
                MIN_MATCH_SCORE
        );
        if (matches.isEmpty()) {
            log.info("[记忆工具] 未检索到相关长期记忆，sessionId={}，query={}", sessionId, trimmedQuery);
            return new MemorySearchResult(
                    trimmedQuery,
                    STATUS_EMPTY,
                    "未检索到与“" + trimmedQuery + "”相关的长期记忆。",
                    List.of(),
                    false
            );
        }

        Map<Long, Optional<MemoryArchiveDocument>> archiveCache = new LinkedHashMap<>();
        List<MemorySearchBundle> bundles = new ArrayList<>();
        boolean truncated = false;
        for (ArchiveEmbeddingService.ArchiveVectorMatch match : matches) {
            MemoryArchiveDocument seedArchive = match == null ? null : match.archive();
            if (seedArchive == null || seedArchive.getId() == null) {
                continue;
            }

            archiveCache.put(seedArchive.getId(), Optional.of(seedArchive));
            List<ArchiveNodeView> groupContext = resolveGroupContext(sessionId, seedArchive, archiveCache);
            GraphExpansionResult graphExpansion = buildGraphExpansion(seedArchive, groupContext, archiveCache);
            bundles.add(new MemorySearchBundle(
                    seedArchive.getId(),
                    trimToEmpty(seedArchive.getGroupId()),
                    match.score(),
                    groupContext,
                    graphExpansion.items(),
                    graphExpansion.truncated()
            ));
            truncated = truncated || graphExpansion.truncated();
        }

        if (bundles.isEmpty()) {
            log.info("[记忆工具] 命中结果无法还原为有效节点，sessionId={}，query={}", sessionId, trimmedQuery);
            return new MemorySearchResult(
                    trimmedQuery,
                    STATUS_EMPTY,
                    "未检索到与“" + trimmedQuery + "”相关的长期记忆。",
                    List.of(),
                    false
            );
        }

        log.info("[记忆工具] 长期记忆检索完成，sessionId={}，命中组数={}，query={}", sessionId, bundles.size(), trimmedQuery);
        return new MemorySearchResult(trimmedQuery, STATUS_OK, "已检索到相关长期记忆。", List.copyOf(bundles), truncated);
    }

    private List<ArchiveNodeView> resolveGroupContext(String sessionId,
                                                      MemoryArchiveDocument seedArchive,
                                                      Map<Long, Optional<MemoryArchiveDocument>> archiveCache) {
        String groupId = trimToEmpty(seedArchive.getGroupId());
        if (!StringUtils.hasText(groupId)) {
            return List.of(toArchiveNodeView(seedArchive));
        }

        List<MemoryArchiveDocument> groupArchives = new ArrayList<>();
        RecentEventGroupDocument group = recentEventGroupService.findById(sessionId, groupId).orElse(null);
        if (group != null && group.getArchiveIds() != null && !group.getArchiveIds().isEmpty()) {
            for (Long archiveId : group.getArchiveIds()) {
                MemoryArchiveDocument archive = loadArchive(archiveId, archiveCache);
                if (archive != null) {
                    groupArchives.add(archive);
                }
            }
        }

        if (groupArchives.isEmpty()) {
            for (MemoryArchiveDocument archive : archiveStore.findAllOrderByIdAsc(sessionId)) {
                if (archive != null && groupId.equals(trimToEmpty(archive.getGroupId()))) {
                    archiveCache.putIfAbsent(archive.getId(), Optional.of(archive));
                    groupArchives.add(archive);
                }
            }
        }

        if (groupArchives.isEmpty()) {
            groupArchives.add(seedArchive);
        }

        groupArchives.sort(Comparator
                .comparing(MemoryArchiveDocument::getGroupOrder, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(MemoryArchiveDocument::getId, Comparator.nullsLast(Long::compareTo)));
        return groupArchives.stream()
                .map(this::toArchiveNodeView)
                .toList();
    }

    private GraphExpansionResult buildGraphExpansion(MemoryArchiveDocument seedArchive,
                                                     List<ArchiveNodeView> groupContext,
                                                     Map<Long, Optional<MemoryArchiveDocument>> archiveCache) {
        String seedGroupId = trimToEmpty(seedArchive.getGroupId());
        LinkedHashMap<Long, GraphExpansionItem> selected = new LinkedHashMap<>();

        boolean truncated = appendWithCapacity(
                selected,
                buildSeedExternalTwoHopCandidates(seedArchive, seedGroupId, archiveCache)
        );
        if (selected.size() < MAX_GRAPH_EXPANSION_SIZE) {
            truncated = appendWithCapacity(
                    selected,
                    buildSameGroupExternalOneHopCandidates(seedGroupId, groupContext, archiveCache)
            ) || truncated;
        }

        return new GraphExpansionResult(List.copyOf(selected.values()), truncated);
    }

    private List<GraphExpansionItem> buildSeedExternalTwoHopCandidates(MemoryArchiveDocument seedArchive,
                                                                       String seedGroupId,
                                                                       Map<Long, Optional<MemoryArchiveDocument>> archiveCache) {
        LinkedHashMap<Long, GraphExpansionItem> candidates = new LinkedHashMap<>();
        Long seedArchiveId = seedArchive.getId();

        for (ArchiveLink firstHopLink : safeLinks(seedArchive.getLinks())) {
            MemoryArchiveDocument intermediateArchive = loadArchive(firstHopLink.getTargetArchiveId(), archiveCache);
            if (intermediateArchive == null || intermediateArchive.getId() == null || intermediateArchive.getId().equals(seedArchiveId)) {
                continue;
            }

            for (ArchiveLink secondHopLink : safeLinks(intermediateArchive.getLinks())) {
                MemoryArchiveDocument targetArchive = loadArchive(secondHopLink.getTargetArchiveId(), archiveCache);
                if (targetArchive != null && intermediateArchive.getId().equals(targetArchive.getId())) {
                    continue;
                }
                if (!isExternalCandidate(targetArchive, seedGroupId, seedArchiveId)) {
                    continue;
                }

                candidates.putIfAbsent(targetArchive.getId(), new GraphExpansionItem(
                        PRIORITY_SEED_EXTERNAL_TWO_HOP,
                        seedArchiveId,
                        targetArchive.getId(),
                        trimToEmpty(targetArchive.getGroupId()),
                        targetArchive.getGroupOrder(),
                        trimToEmpty(targetArchive.getTopic()),
                        trimToEmpty(targetArchive.getKeywordSummary()),
                        trimToEmpty(targetArchive.getNarrative()),
                        2,
                        List.of(seedArchiveId, intermediateArchive.getId(), targetArchive.getId()),
                        List.of(trimToEmpty(firstHopLink.getRelation()), trimToEmpty(secondHopLink.getRelation()))
                ));
            }
        }
        return List.copyOf(candidates.values());
    }

    private List<GraphExpansionItem> buildSameGroupExternalOneHopCandidates(String seedGroupId,
                                                                            List<ArchiveNodeView> groupContext,
                                                                            Map<Long, Optional<MemoryArchiveDocument>> archiveCache) {
        LinkedHashMap<Long, GraphExpansionItem> candidates = new LinkedHashMap<>();
        for (ArchiveNodeView groupArchiveView : groupContext) {
            MemoryArchiveDocument sourceArchive = loadArchive(groupArchiveView.archiveId(), archiveCache);
            if (sourceArchive == null || sourceArchive.getId() == null) {
                continue;
            }

            for (ArchiveLink link : safeLinks(sourceArchive.getLinks())) {
                MemoryArchiveDocument targetArchive = loadArchive(link.getTargetArchiveId(), archiveCache);
                if (!isExternalCandidate(targetArchive, seedGroupId, sourceArchive.getId())) {
                    continue;
                }

                candidates.putIfAbsent(targetArchive.getId(), new GraphExpansionItem(
                        PRIORITY_SAME_GROUP_EXTERNAL_ONE_HOP,
                        sourceArchive.getId(),
                        targetArchive.getId(),
                        trimToEmpty(targetArchive.getGroupId()),
                        targetArchive.getGroupOrder(),
                        trimToEmpty(targetArchive.getTopic()),
                        trimToEmpty(targetArchive.getKeywordSummary()),
                        trimToEmpty(targetArchive.getNarrative()),
                        1,
                        List.of(sourceArchive.getId(), targetArchive.getId()),
                        List.of(trimToEmpty(link.getRelation()))
                ));
            }
        }
        return List.copyOf(candidates.values());
    }

    private boolean appendWithCapacity(LinkedHashMap<Long, GraphExpansionItem> selected,
                                       List<GraphExpansionItem> candidates) {
        boolean truncated = false;
        for (GraphExpansionItem candidate : candidates) {
            if (selected.containsKey(candidate.archiveId())) {
                continue;
            }
            if (selected.size() >= MAX_GRAPH_EXPANSION_SIZE) {
                truncated = true;
                break;
            }
            selected.put(candidate.archiveId(), candidate);
        }
        return truncated;
    }

    private boolean isExternalCandidate(MemoryArchiveDocument archive, String seedGroupId, Long sourceArchiveId) {
        if (archive == null || archive.getId() == null || archive.getId().equals(sourceArchiveId)) {
            return false;
        }
        return !seedGroupId.equals(trimToEmpty(archive.getGroupId()));
    }

    private MemoryArchiveDocument loadArchive(Long archiveId,
                                              Map<Long, Optional<MemoryArchiveDocument>> archiveCache) {
        if (archiveId == null) {
            return null;
        }

        Optional<MemoryArchiveDocument> cachedArchive = archiveCache.get(archiveId);
        if (cachedArchive != null) {
            return cachedArchive.orElse(null);
        }

        Optional<MemoryArchiveDocument> loadedArchive = archiveStore.findById(archiveId);
        archiveCache.put(archiveId, loadedArchive);
        return loadedArchive.orElse(null);
    }

    private List<ArchiveLink> safeLinks(List<ArchiveLink> links) {
        if (links == null || links.isEmpty()) {
            return List.of();
        }

        List<ArchiveLink> safeLinks = new ArrayList<>();
        for (ArchiveLink link : links) {
            if (link != null && link.getTargetArchiveId() != null) {
                safeLinks.add(link);
            }
        }
        return List.copyOf(safeLinks);
    }

    private ArchiveNodeView toArchiveNodeView(MemoryArchiveDocument archive) {
        return new ArchiveNodeView(
                archive.getId(),
                trimToEmpty(archive.getGroupId()),
                archive.getGroupOrder(),
                trimToEmpty(archive.getTopic()),
                trimToEmpty(archive.getKeywordSummary()),
                trimToEmpty(archive.getNarrative())
        );
    }

    private String trimToEmpty(String text) {
        return text == null ? "" : text.trim();
    }

    private record GraphExpansionResult(List<GraphExpansionItem> items, boolean truncated) {
    }

    public record MemorySearchResult(String query,
                                     String status,
                                     String message,
                                     List<MemorySearchBundle> bundles,
                                     boolean truncated) {
    }

    public record MemorySearchBundle(Long seedArchiveId,
                                     String seedGroupId,
                                     double seedScore,
                                     List<ArchiveNodeView> groupContext,
                                     List<GraphExpansionItem> graphExpansion,
                                     boolean graphExpansionTruncated) {
    }

    public record ArchiveNodeView(Long archiveId,
                                  String groupId,
                                  Integer groupOrder,
                                  String topic,
                                  String keywordSummary,
                                  String narrative) {
    }

    public record GraphExpansionItem(String priorityBucket,
                                     Long sourceArchiveId,
                                     Long archiveId,
                                     String groupId,
                                     Integer groupOrder,
                                     String topic,
                                     String keywordSummary,
                                     String narrative,
                                     int hopCount,
                                     List<Long> pathArchiveIds,
                                     List<String> pathRelations) {
    }
}
