package p1.infrastructure.vector;

import lombok.CustomLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import p1.infrastructure.logging.LogDomain;
import p1.infrastructure.logging.LogOutcome;
import p1.config.prop.AssistantProperties;
import p1.utils.SessionUtil;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 按 session 和库类型路由到底层 Lucene 向量库。
 * 这里负责多实例、多目录的创建和复用。
 */
@Service
@RequiredArgsConstructor
@CustomLog
public class SessionMemoryVectorStoreFactory {

    private final AssistantProperties props;
    private final ConcurrentMap<StoreKey, MemoryVectorStore> stores = new ConcurrentHashMap<>();

    /**
     * 获取指定 session、指定库类型对应的向量库实例。
     * 相同的 session + library 会复用同一个底层 store。
     */
    public MemoryVectorStore getStore(String sessionId, MemoryVectorLibrary library) {
        String normalizedSessionId = SessionUtil.normalizeSessionId(sessionId);
        StoreKey key = new StoreKey(normalizedSessionId, library);
        return stores.computeIfAbsent(key, ignored -> {
            Path storePath = resolveStorePath(normalizedSessionId, library);
            log.info(LogDomain.MEMORY, "vector.store_created", LogOutcome.SUCCEEDED, "sessionId", normalizedSessionId, "library", library.name(), "storePath", storePath);
            return new LuceneMemoryVectorStore(storePath);
        });
    }

    /**
     * 计算某个 session、某个库类型在磁盘上的实际目录。
     * 如果目录不存在，会返回默认值"default"的路径。
     */
    public Path resolveStorePath(String sessionId, MemoryVectorLibrary library) {
        String normalizedSessionId = SessionUtil.normalizeSessionId(sessionId);
        return Paths.get(props.getEmbeddingStore().getPath(), "sessions", normalizedSessionId, library.directoryName());
    }

    private record StoreKey(String sessionId, MemoryVectorLibrary library) {
    }
}
