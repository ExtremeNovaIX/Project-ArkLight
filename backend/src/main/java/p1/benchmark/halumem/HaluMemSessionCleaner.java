package p1.benchmark.halumem;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import p1.component.agent.rp.context.SummaryCacheManager;
import p1.config.prop.AssistantProperties;
import p1.service.ChatLogRepository;
import p1.utils.SessionUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class HaluMemSessionCleaner {

    private final AssistantProperties assistantProperties;
    private final ChatLogRepository chatLogRepository;
    private final SummaryCacheManager summaryCacheManager;

    public HaluMemResetResponse reset(HaluMemResetRequest request) {
        Set<String> sessionIds = new LinkedHashSet<>();
        if (request != null && request.sessionIds() != null) {
            for (String sessionId : request.sessionIds()) {
                if (StringUtils.hasText(sessionId)) {
                    sessionIds.add(SessionUtil.normalizeSessionId(sessionId));
                }
            }
        }

        AtomicInteger deletedFileCount = new AtomicInteger();
        for (String sessionId : sessionIds) {
            chatLogRepository.deleteBySessionId(sessionId);
            summaryCacheManager.clear(sessionId);
            deleteTree(mdSessionRoot(sessionId), deletedFileCount);
            deleteTree(mdSystemSessionRoot(sessionId), deletedFileCount);
            deleteTree(vectorSessionRoot(sessionId), deletedFileCount);
        }

        return new HaluMemResetResponse(
                sessionIds.size(),
                sessionIds.size(),
                deletedFileCount.get()
        );
    }

    private Path mdSessionRoot(String sessionId) {
        return Paths.get(assistantProperties.getMdRepository().getPath(), "sessions", sessionId);
    }

    private Path mdSystemSessionRoot(String sessionId) {
        return Paths.get(assistantProperties.getMdRepository().getPath(), "_system", "sessions", sessionId);
    }

    private Path vectorSessionRoot(String sessionId) {
        return Paths.get(assistantProperties.getEmbeddingStore().getPath(), "sessions", sessionId);
    }

    private void deleteTree(Path root, AtomicInteger deletedFileCount) {
        if (root == null || !Files.exists(root)) {
            return;
        }

        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> deletePath(path, deletedFileCount));
        } catch (IOException e) {
            throw new IllegalStateException("failed to delete benchmark session tree: " + root, e);
        }
    }

    private void deletePath(Path path, AtomicInteger deletedFileCount) {
        try {
            Files.deleteIfExists(path);
            deletedFileCount.incrementAndGet();
        } catch (IOException e) {
            throw new IllegalStateException("failed to delete benchmark path: " + path, e);
        }
    }
}
