package p1.component.agent.router;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import dev.langchain4j.model.chat.ChatModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * 指令路由器 Bean 配置。
 * <p>
 * 启动时检测 {@code llm/} 目录下是否存在 sidecar 可执行文件和模型文件，
 * 选择 {@link LocalModelInstructionRouter} 或 {@link ChatModelInstructionRouter}。
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class InstructionRouterConfig {

    private final InstructionRouterModelConfig modelConfig;
    private final InstructionRouterTaskRegistry taskRegistry;
    @Qualifier("checkerChatModel")
    private final ChatModel checkerChatModel;

    /**
     * 根据本地 LLM 目录是否可用，选择路由器实现。
     *
     * @return 指令路由器
     */
    @Bean
    public InstructionRouter instructionRouter() {
        if (isLocalModelAvailable()) {
            log.info("[指令路由] 检测到本地模型文件，使用 LocalModelInstructionRouter");
            return new LocalModelInstructionRouter(modelConfig, taskRegistry);
        }
        log.info("[指令路由] 未检测到本地模型文件，降级使用 ChatModelInstructionRouter");
        return new ChatModelInstructionRouter(checkerChatModel, taskRegistry);
    }

    /**
     * 检测 llm 目录下是否有可用的 sidecar 和模型文件。
     */
    private boolean isLocalModelAvailable() {
        Path llmPath = Path.of("llm");
        if (!Files.isDirectory(llmPath)) {
            log.debug("[指令路由] llm 目录不存在: {}", llmPath.toAbsolutePath());
            return false;
        }

        boolean hasServer = Files.isRegularFile(llmPath.resolve("llama-server.exe"));
        if (!hasServer) {
            log.debug("[指令路由] llama-server.exe 不存在");
            return false;
        }

        Path modelsDir = llmPath.resolve("models");
        if (!Files.isDirectory(modelsDir)) {
            log.debug("[指令路由] models 目录不存在");
            return false;
        }

        try (Stream<Path> files = Files.list(modelsDir)) {
            boolean hasGguf = files.anyMatch(f -> f.getFileName().toString().endsWith(".gguf"));
            if (!hasGguf) {
                log.debug("[指令路由] models 目录下没有 .gguf 文件");
                return false;
            }
        } catch (Exception e) {
            log.debug("[指令路由] 无法列出 models 目录: {}", e.getMessage());
            return false;
        }

        return true;
    }
}
