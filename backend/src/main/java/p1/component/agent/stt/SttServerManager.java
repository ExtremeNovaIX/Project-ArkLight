package p1.component.agent.stt;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * sherpa-onnx WebSocket 服务器生命周期管理。
 * <p>
 * 启动时自动拉起 STT sidecar 进程，关闭时终止。
 * 每 30 秒检测进程存活，崩溃后自动重启（最多 3 次）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SttServerManager {

    private static final int MAX_RESTART_ATTEMPTS = 3;

    private final SttConfig config;
    private Process process;
    private int restartCount;

    @PostConstruct
    public void startIfConfigured() {
        if (!config.runtimeAutoStartEnabled()) {
            return;
        }
        if (!Files.isRegularFile(Path.of(config.executablePath()))) {
            log.info("[STT] sherpa-onnx 可执行文件不存在，跳过自动启动: {}", config.executablePath());
            return;
        }
        startProcess();
    }

    @PreDestroy
    public void stopIfStarted() {
        stopProcess();
    }

    /**
     * 定期健康检查，检测进程崩溃并自动重启。
     */
    @Scheduled(fixedDelay = 30_000)
    public void healthCheck() {
        if (process == null) {
            return;
        }
        if (!process.isAlive()) {
            int exitCode = process.exitValue();
            log.warn("[STT] sherpa-onnx 进程已退出: exitCode={}, restartCount={}", exitCode, restartCount);
            if (restartCount < MAX_RESTART_ATTEMPTS) {
                log.info("[STT] 尝试重启 sherpa-onnx (第 {}/{} 次)", restartCount + 1, MAX_RESTART_ATTEMPTS);
                startProcess();
            } else {
                log.error("[STT] sherpa-onnx 已达到最大重启次数 ({}), 放弃重启", MAX_RESTART_ATTEMPTS);
            }
        }
    }

    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    private void startProcess() {
        List<String> command = config.runtimeCommand();
        if (command == null || command.isEmpty()) {
            log.warn("[STT] 未配置启动命令");
            return;
        }

        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (config.runtimeWorkingDirectory() != null && !config.runtimeWorkingDirectory().isBlank()) {
                builder.directory(Path.of(config.runtimeWorkingDirectory()).toFile());
            }
            builder.redirectErrorStream(true);
            Path logFile = Path.of(config.logFilePath()).toAbsolutePath().normalize();
            Path logParent = logFile.getParent();
            if (logParent != null) {
                Files.createDirectories(logParent);
            }
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            process = builder.start();
            restartCount++;
            log.info("[STT] sherpa-onnx 进程已启动: port={}, modelType={}, restartCount={}",
                    config.serverPort(), config.modelType(), restartCount);
            long waitMs = Math.max(0L, config.runtimeStartupWaitMs());
            if (waitMs > 0) {
                Thread.sleep(waitMs);
            }
        } catch (IOException e) {
            log.warn("[STT] sherpa-onnx 进程启动失败: reason={}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[STT] 等待 sherpa-onnx 启动时被中断");
        }
    }

    private void stopProcess() {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        log.info("[STT] sherpa-onnx 进程已请求停止");
    }
}
