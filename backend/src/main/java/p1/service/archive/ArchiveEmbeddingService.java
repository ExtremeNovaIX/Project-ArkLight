package p1.service.archive;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import p1.infrastructure.vector.ArchiveVectorLibrary;
import p1.infrastructure.vector.MemoryVectorDocument;
import p1.infrastructure.vector.MemoryVectorDocumentIds;
import p1.model.document.MemoryArchiveDocument;
import p1.service.EmbeddingService;
import p1.service.markdown.MemoryArchiveStore;
import p1.utils.SessionUtil;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ArchiveEmbeddingService {

    private final EmbeddingService embeddingService;
    private final MemoryArchiveStore archiveStore;

    /**
     * 检索指定 archive 子库，并把命中结果还原成 archive 文档列表。
     *
     * @param library    archive 子库，用于指定检索范围
     * @param query      向量查询文本
     * @param maxResults 最大返回结果数
     * @param minScore   最小分数阈值
     * @return 符合条件的 archive 文档列表
     */
    public List<ArchiveVectorMatch> searchArchiveMatches(String sessionId,
                                                         ArchiveVectorLibrary library,
                                                         String query,
                                                         int maxResults,
                                                         double minScore) {
        return embeddingService.searchEmbedding(sessionId, library.rootLibrary(), query, maxResults, minScore)
                .matches().stream()
                .map(this::toArchiveMatch)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 默认使用当前上下文 session 检索长期 archive 库。
     */
    public List<ArchiveVectorMatch> searchArchiveMatches(String query, int maxResults, double minScore) {
        return searchArchiveMatches(currentSessionId(), ArchiveVectorLibrary.ARCHIVE, query, maxResults, minScore);
    }

    /**
     * 把一组 archive 文档写入指定 archive 子库。
     *
     * @return archive存入向量库后对应的ID列表，一般用于事件组
     */
    public List<String> indexArchives(String sessionId,
                                      ArchiveVectorLibrary library,
                                      List<MemoryArchiveDocument> archives,
                                      String groupId) {
        String normalizedSessionId = SessionUtil.normalizeSessionId(sessionId);
        // ARCHIVE 和 RECENT_24H 共用同一套 archive 输入，但构建出的向量文档粒度不同。
        List<MemoryVectorDocument> documents = buildArchiveDocuments(normalizedSessionId, library, archives, groupId);
        if (documents.isEmpty()) {
            return List.of();
        }

        List<String> indexedDocumentIds = new ArrayList<>();
        try {
            for (MemoryVectorDocument document : documents) {
                embeddingService.indexDocument(normalizedSessionId, library.rootLibrary(), document);
                indexedDocumentIds.add(document.documentId());
            }
            log.info("[Archive 向量写入] sessionId={} 完成，library={}，documentCount={}",
                    normalizedSessionId, library.name(), indexedDocumentIds.size());
            return List.copyOf(indexedDocumentIds);
        } catch (RuntimeException e) {
            if (library == ArchiveVectorLibrary.ARCHIVE && shouldFallbackToRebuild(e)) {
                log.warn("[Archive 向量降级] sessionId={} 的长期 archive 增量写入失败，改为全量重建",
                        normalizedSessionId, e);
                rebuildArchiveEmbeddings(normalizedSessionId);
                return documents.stream().map(MemoryVectorDocument::documentId).toList();
            }

            deleteDocuments(normalizedSessionId, library, indexedDocumentIds);
            throw e;
        }
    }

    /**
     * 删除 archive 子库中的一批向量文档。
     */
    public void deleteDocuments(String sessionId, ArchiveVectorLibrary library, List<String> documentIds) {
        embeddingService.deleteDocuments(sessionId, library.rootLibrary(), documentIds);
    }

    /**
     * 重建指定 session 的长期 archive 向量库。
     */
    public void rebuildArchiveEmbeddings(String sessionId) {
        String normalizedSessionId = SessionUtil.normalizeSessionId(sessionId);

        List<MemoryVectorDocument> documents = archiveStore.findAllOrderByIdAsc(normalizedSessionId).stream()
                .filter(Objects::nonNull)
                .map(archive -> buildArchiveDocument(normalizedSessionId, archive))
                .filter(Objects::nonNull)
                .toList();

        embeddingService.rebuildLibrary(normalizedSessionId, ArchiveVectorLibrary.ARCHIVE.rootLibrary(), documents);
    }

    /**
     * 重建所有 session 的长期 archive 向量库。
     */
    public void rebuildAllArchiveEmbeddings() {
        Map<String, List<MemoryArchiveDocument>> archivesBySession = archiveStore.findAllOrderByIdAsc().stream()
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(archive -> SessionUtil.normalizeSessionId(archive.getSessionId())));

        for (Map.Entry<String, List<MemoryArchiveDocument>> entry : archivesBySession.entrySet()) {
            List<MemoryVectorDocument> documents = entry.getValue().stream()
                    .map(archive -> buildArchiveDocument(entry.getKey(), archive))
                    .filter(Objects::nonNull)
                    .toList();
            embeddingService.rebuildLibrary(entry.getKey(), ArchiveVectorLibrary.ARCHIVE.rootLibrary(), documents);
        }
    }

    /**
     * 按目标 archive 子库构建对应的向量文档列表。
     */
    private List<MemoryVectorDocument> buildArchiveDocuments(String sessionId,
                                                             ArchiveVectorLibrary library,
                                                             List<MemoryArchiveDocument> archives,
                                                             String groupId) {
        List<MemoryArchiveDocument> persistedArchives = persistedArchives(archives);
        if (persistedArchives.isEmpty()) {
            return List.of();
        }

        return switch (library) {
            case ARCHIVE -> persistedArchives.stream()
                    .map(archive -> buildArchiveDocument(sessionId, archive))
                    .filter(Objects::nonNull)
                    .toList();
            case RECENT_24H -> buildRecent24hDocuments(sessionId, groupId, persistedArchives);
        };
    }

    /**
     * 构建长期 archive 库使用的向量文档。
     */
    private MemoryVectorDocument buildArchiveDocument(String sessionId, @NonNull MemoryArchiveDocument archive) {
        if (archive.getId() == null) {
            return null;
        }

        String documentId = MemoryVectorDocumentIds.archiveDocumentId(archive.getId());
        return buildVectorDocument(sessionId, archive, documentId, Map.of());
    }

    /**
     * 构建 recent-24h 库使用的一组向量文档。
     * 组内每个事件都参与检索，但它们会携带相同的 groupId 和 headArchiveId 元数据。
     */
    private List<MemoryVectorDocument> buildRecent24hDocuments(String sessionId, String groupId, List<MemoryArchiveDocument> archives) {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("RECENT_24H 写入必须提供 groupId");
        }

        MemoryArchiveDocument headArchive = archives.getFirst();
        Long headArchiveId = headArchive == null ? null : headArchive.getId();

        List<MemoryVectorDocument> documents = new ArrayList<>();
        for (int index = 0; index < archives.size(); index++) {
            MemoryArchiveDocument archive = archives.get(index);
            if (archive == null || archive.getId() == null) {
                continue;
            }

            // recent-24h 仍然按“单事件”入向量库，但会附带同一个 groupId，供命中后按组聚合。
            String documentId = MemoryVectorDocumentIds.recent24hDocumentId(groupId, archive.getId());
            Map<String, String> extraMetadata = new LinkedHashMap<>();
            extraMetadata.put("group_id", groupId);
            extraMetadata.put("group_order", String.valueOf(index));
            if (headArchiveId != null) {
                extraMetadata.put("head_archive_id", String.valueOf(headArchiveId));
            }

            MemoryVectorDocument document = buildVectorDocument(sessionId, archive, documentId, extraMetadata);
            if (document != null) {
                documents.add(document);
            }
        }
        return documents;
    }

    /**
     * 构建通用向量文档。
     * archive 和 recent-24h 两类库都复用这套构建逻辑，只是 documentId 和附加元数据不同。
     */
    private MemoryVectorDocument buildVectorDocument(String sessionId,
                                                     @NonNull MemoryArchiveDocument archive,
                                                     @NonNull String documentId,
                                                     Map<String, String> extraMetadata) {
        String indexText = buildArchiveIndexText(archive);
        if (indexText.isBlank()) {
            return null;
        }

        // metadata 承担“向量命中 -> 还原 archive / event-group 上下文”的反查职责。
        Metadata metadata = new Metadata();
        metadata.put("archive_id", String.valueOf(archive.getId()));
        metadata.put("vector_doc_id", documentId);
        if (extraMetadata != null) {
            for (Map.Entry<String, String> entry : extraMetadata.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    metadata.put(entry.getKey(), entry.getValue());
                }
            }
        }

        TextSegment segment = TextSegment.from(indexText, metadata);
        return new MemoryVectorDocument(documentId, embeddingService.embed(sessionId, segment), segment);
    }

    /**
     * 把检索命中结果还原成 archive匹配结果。
     */
    private ArchiveVectorMatch toArchiveMatch(EmbeddingMatch<TextSegment> match) {
        if (match == null || match.embedded() == null || match.embedded().metadata() == null) {
            return null;
        }

        String archiveIdText = match.embedded().metadata().getString("archive_id");
        if (archiveIdText == null || archiveIdText.isBlank()) {
            log.warn("[向量检索结果跳过] 命中结果缺少 archive_id 元数据");
            return null;
        }

        Long archiveId;
        try {
            archiveId = Long.parseLong(archiveIdText);
        } catch (NumberFormatException e) {
            log.warn("[向量检索结果跳过] 命中结果的 archive_id={} 不是有效数字", archiveIdText);
            return null;
        }

        MemoryArchiveDocument archive = archiveStore.findById(archiveId).orElse(null);
        if (archive == null) {
            log.warn("[向量检索结果跳过] archive_id={} 对应的 archive 不存在", archiveId);
            return null;
        }

        Metadata metadata = match.embedded().metadata();
        String groupId = metadata.getString("group_id");
        Long headArchiveId = parseLong(metadata.getString("head_archive_id"));
        Integer groupOrder = parseInteger(metadata.getString("group_order"));
        String vectorDocumentId = metadata.getString("vector_doc_id");
        return new ArchiveVectorMatch(archive, match.score(), vectorDocumentId, groupId, headArchiveId, groupOrder);
    }

    /**
     * 决定 archive 的索引文本。
     * 现在仍然优先使用 keywordSummary；没有时退回 detailedSummary。
     */
    private String buildArchiveIndexText(MemoryArchiveDocument archive) {
        String keywordSummary = normalize(archive.getKeywordSummary());
        if (!keywordSummary.isBlank()) {
            return keywordSummary;
        }

        String detailedSummary = normalize(archive.getNarrative());
        if (!detailedSummary.isBlank()) {
            log.warn("[向量索引文本降级] archiveId={} 缺少 keywordSummary，改用 detailedSummary 写入向量库", archive.getId());
        }
        return detailedSummary;
    }

    private List<MemoryArchiveDocument> persistedArchives(List<MemoryArchiveDocument> archives) {
        if (archives == null || archives.isEmpty()) {
            return List.of();
        }
        return archives.stream()
                .filter(Objects::nonNull)
                .filter(archive -> archive.getId() != null)
                .toList();
    }

    private String currentSessionId() {
        return SessionUtil.normalizeSessionId(MDC.get("sessionId"));
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim();
    }

    private boolean shouldFallbackToRebuild(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null
                    && message.contains("cannot change field \"embedding\"")
                    && message.contains("vector similarity function")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Long.parseLong(value);
    }

    private Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Integer.parseInt(value);
    }

    /**
     * 向量检索命中结果的 archive 匹配结果。
     *
     * @param archive          匹配到的 Archive 文档实体
     * @param score            向量相似度分值（通常分值越高表示越相关）
     * @param vectorDocumentId 在向量数据库中的唯一标识 ID
     * @param groupId          关联的分组 ID，用于按组聚合检索结果
     * @param headArchiveId    该组的头部 Archive ID，用于标识逻辑上的起始节点
     * @param groupOrder       在分组内的排序序号（例如在该文档块在原文档中的位置）
     */
    public record ArchiveVectorMatch(MemoryArchiveDocument archive,
                                     double score,
                                     String vectorDocumentId,
                                     String groupId,
                                     Long headArchiveId,
                                     Integer groupOrder) {
    }
}
