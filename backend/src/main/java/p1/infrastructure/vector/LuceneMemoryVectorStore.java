package p1.infrastructure.vector;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import lombok.CustomLog;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.*;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import p1.infrastructure.logging.LogDomain;
import p1.infrastructure.logging.LogOutcome;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@CustomLog
public class LuceneMemoryVectorStore implements MemoryVectorStore {

    private static final String FIELD_DOC_ID = "doc_id";
    private static final String FIELD_ARCHIVE_ID = "archive_id";
    private static final String FIELD_TEXT = "text";
    private static final String FIELD_VECTOR = "embedding";
    private static final String FIELD_METADATA_PREFIX = "meta_";
    private static final int MAX_LUCENE_VECTOR_DIMENSIONS = 1024;

    private final Path storePath;
    private final ReadWriteLock storeLock = new ReentrantReadWriteLock();

    public LuceneMemoryVectorStore(Path storePath) {
        this.storePath = storePath;
    }

    @Override
    public String backendType() {
        return "lucene-native";
    }

    @Override
    public boolean supportsDocumentUpdate() {
        return true;
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(Embedding queryEmbedding, int maxResults, double minScore) {
        storeLock.readLock().lock();
        try (Directory directory = openDirectory()) {
            if (!DirectoryReader.indexExists(directory)) {
                return new EmbeddingSearchResult<>(List.of());
            }

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                if (reader.numDocs() == 0) {
                    return new EmbeddingSearchResult<>(List.of());
                }

                IndexSearcher searcher = new IndexSearcher(reader);
                TopDocs topDocs = searcher.search(
                        new KnnFloatVectorQuery(FIELD_VECTOR, luceneVector(queryEmbedding.vector()), maxResults),
                        maxResults
                );

                List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
                for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                    if (scoreDoc.score < minScore) {
                        continue;
                    }

                    Document document = searcher.doc(scoreDoc.doc);
                    String docId = document.get(FIELD_DOC_ID);
                    String archiveId = document.get(FIELD_ARCHIVE_ID);
                    String text = document.get(FIELD_TEXT);

                    Metadata metadata = restoreMetadata(document);
                    if (!metadata.containsKey("archive_id") && archiveId != null) {
                        metadata.put("archive_id", archiveId);
                    }
                    if (!metadata.containsKey("vector_doc_id") && docId != null) {
                        metadata.put("vector_doc_id", docId);
                    }

                    TextSegment segment = TextSegment.from(text == null ? "" : text, metadata);
                    matches.add(new EmbeddingMatch<>((double) scoreDoc.score, docId, null, segment));
                }
                return new EmbeddingSearchResult<>(matches);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("搜索 Lucene 向量索引失败", e);
        } finally {
            storeLock.readLock().unlock();
        }
    }

    @Override
    public void add(MemoryVectorDocument document) {
        storeLock.writeLock().lock();
        try (Directory directory = openDirectory();
             IndexWriter writer = openWriter(directory)) {
            writer.addDocument(toLuceneDocument(document));
            writer.commit();
        } catch (IOException e) {
            throw new UncheckedIOException("写入 Lucene 向量索引失败", e);
        } finally {
            storeLock.writeLock().unlock();
        }
    }

    @Override
    public void update(MemoryVectorDocument document) {
        storeLock.writeLock().lock();
        try (Directory directory = openDirectory();
             IndexWriter writer = openWriter(directory)) {
            writer.updateDocument(new Term(FIELD_DOC_ID, document.documentId()), toLuceneDocument(document));
            writer.commit();
        } catch (IOException e) {
            throw new UncheckedIOException("更新 Lucene 向量索引失败", e);
        } finally {
            storeLock.writeLock().unlock();
        }
    }

    @Override
    public void delete(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            return;
        }

        storeLock.writeLock().lock();
        try (Directory directory = openDirectory()) {
            if (!DirectoryReader.indexExists(directory)) {
                return;
            }

            try (IndexWriter writer = openWriter(directory)) {
                writer.deleteDocuments(new Term(FIELD_DOC_ID, documentId));
                writer.commit();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("删除 Lucene 向量索引失败", e);
        } finally {
            storeLock.writeLock().unlock();
        }
    }

    @Override
    public void rebuild(List<MemoryVectorDocument> documents) {
        storeLock.writeLock().lock();
        try (Directory directory = openDirectory();
             IndexWriter writer = openWriter(directory)) {
            writer.deleteAll();
            for (MemoryVectorDocument document : documents) {
                writer.addDocument(toLuceneDocument(document));
            }
            writer.commit();
            log.info(LogDomain.MEMORY, "vector.index_rebuilt", LogOutcome.SUCCEEDED, "documentCount", documents.size());
        } catch (IOException e) {
            throw new UncheckedIOException("重建 Lucene 向量索引失败", e);
        } finally {
            storeLock.writeLock().unlock();
        }
    }

    private Document toLuceneDocument(MemoryVectorDocument document) {
        Document luceneDocument = new Document();
        luceneDocument.add(new StringField(FIELD_DOC_ID, document.documentId(), org.apache.lucene.document.Field.Store.YES));

        Metadata metadata = document.segment().metadata();
        String archiveId = metadata == null ? null : metadata.getString("archive_id");
        if (archiveId != null && !archiveId.isBlank()) {
            luceneDocument.add(new StringField(FIELD_ARCHIVE_ID, archiveId, org.apache.lucene.document.Field.Store.YES));
        }
        if (metadata != null) {
            for (Map.Entry<String, Object> entry : metadata.toMap().entrySet()) {
                Object value = entry.getValue();
                if (value == null) {
                    continue;
                }
                luceneDocument.add(new StoredField(metadataFieldName(entry.getKey()), String.valueOf(value)));
            }
        }

        luceneDocument.add(new StoredField(FIELD_TEXT, document.segment().text()));
        luceneDocument.add(new KnnFloatVectorField(
                FIELD_VECTOR,
                luceneVector(document.embedding().vector()),
                VectorSimilarityFunction.COSINE
        ));
        return luceneDocument;
    }

    private float[] luceneVector(float[] vector) {
        if (vector == null || vector.length <= MAX_LUCENE_VECTOR_DIMENSIONS) {
            return vector;
        }

        float[] truncated = new float[MAX_LUCENE_VECTOR_DIMENSIONS];
        System.arraycopy(vector, 0, truncated, 0, MAX_LUCENE_VECTOR_DIMENSIONS);
        return truncated;
    }

    private Directory openDirectory() throws IOException {
        Files.createDirectories(storePath);
        return FSDirectory.open(storePath);
    }

    private IndexWriter openWriter(Directory directory) throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(new KeywordAnalyzer());
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        return new IndexWriter(directory, config);
    }

    private Metadata restoreMetadata(Document document) {
        Metadata metadata = new Metadata();
        for (IndexableField field : document.getFields()) {
            String fieldName = field.name();
            if (!fieldName.startsWith(FIELD_METADATA_PREFIX)) {
                continue;
            }
            String value = document.get(fieldName);
            if (value != null) {
                metadata.put(fieldName.substring(FIELD_METADATA_PREFIX.length()), value);
            }
        }
        return metadata;
    }

    private String metadataFieldName(String key) {
        return FIELD_METADATA_PREFIX + key;
    }
}
