package p1.component.agent.router;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 指令路由本地模型 sidecar 生命周期管理。
 * <p>
 * 第一版只负责按配置可选拉起外部进程，例如 llama.cpp server。模型文件、命令参数和工作目录都由 yaml 提供，
 * 这样路由组件保持通用，不绑定某个具体模型发行格式。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InstructionRouterRuntimeService {

    private final InstructionRouterModelConfig modelConfig;
    private Process process;

    /**
     * Spring 启动后按配置拉起本地路由模型。
     */
    @PostConstruct
    public void startIfConfigured() {
        if (!modelConfig.runtimeAutoStartEnabled()) {
            return;
        }
        if (!Files.isRegularFile(Path.of("llm/llama-server.exe"))) {
            log.info("[指令路由] 本地模型可执行文件不存在，跳过自动启动");
            return;
        }
        List<String> command = modelConfig.runtimeCommand();
        if (command == null || command.isEmpty()) {
            log.warn("[指令路由] 已开启 auto-start，但未配置启动命令");
            return;
        }

        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (modelConfig.runtimeWorkingDirectory() != null && !modelConfig.runtimeWorkingDirectory().isBlank()) {
                builder.directory(Path.of(modelConfig.runtimeWorkingDirectory()).toFile());
            }
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            log.info("[指令路由] 本地路由模型进程已启动: command={}", command);
            long waitMs = Math.max(0L, modelConfig.runtimeStartupWaitMs());
            if (waitMs > 0) {
                Thread.sleep(waitMs);
            }
        } catch (IOException e) {
            log.warn("[指令路由] 本地路由模型进程启动失败: command={}, reason={}", command, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[指令路由] 等待本地路由模型启动时被中断");
        }
    }

    /**
     * Spring 关闭时终止由本服务启动的 sidecar。
     */
    @PreDestroy
    public void stopIfStarted() {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        log.info("[指令路由] 本地路由模型进程已请求停止");
    }
}
