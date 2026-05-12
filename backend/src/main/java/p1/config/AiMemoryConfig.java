package p1.config;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import p1.component.agent.memory.ArchivableChatMemory;
import p1.component.agent.memory.ChatMemoryAppender;
import p1.component.agent.memory.MemoryAsyncCompressor;
import p1.config.prop.AssistantProperties;
import p1.config.prop.LockProperties;
import p1.service.ChatLogRepository;
import p1.service.markdown.RawMdService;
import p1.utils.SessionUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RP 对话记忆 provider 配置。
 */
@Configuration
@RequiredArgsConstructor
public class AiMemoryConfig {

    private final AssistantProperties props;
    private final Map<String, ArchivableChatMemory> memoryCache = new ConcurrentHashMap<>();

    /**
     * 按 RP 会话构建可归档聊天记忆。
     *
     * @param compressor        异步压缩器
     * @param dbAppender        记忆追加器
     * @param rawMdService      原始 markdown 服务
     * @param lockProperties    锁配置
     * @param chatLogRepository 对话日志仓库
     * @return ChatMemory provider
     */
    @Bean
    public ChatMemoryProvider chatMemoryProvider(MemoryAsyncCompressor compressor,
                                                 ChatMemoryAppender dbAppender,
                                                 RawMdService rawMdService,
                                                 LockProperties lockProperties,
                                                 ChatLogRepository chatLogRepository) {
        return memoryId -> {
            String sessionId = SessionUtil.normalizeSessionId(memoryId.toString());
            return memoryCache.computeIfAbsent(sessionId,
                    id -> new ArchivableChatMemory(
                            id, compressor, dbAppender, rawMdService, props, lockProperties, chatLogRepository));
        };
    }
}
