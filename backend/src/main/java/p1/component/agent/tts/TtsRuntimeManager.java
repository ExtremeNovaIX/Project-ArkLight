package p1.component.agent.tts;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * TTS sidecar 生命周期管理。
 * <p>
 * 该类按当前 {@code tts.provider} 选择对应 runtime 配置，只负责拉起、健康检查和关闭外部服务；
 * 模型、权重、依赖仍由各 TTS 项目自己管理，Spring 后端不会把 TTS 模型打进 Java 包里。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TtsRuntimeManager {

    private final TtsConfig config;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private Process process;
    private int restartCount;

    /**
     * Spring 启动后按当前 provider 尝试拉起对应 sidecar。
     */
    @PostConstruct
    public void startIfConfigured() {
        RuntimeProfile profile = activeRuntimeProfile();
        if (profile == null) {
            return;
        }
        if (isServiceHealthy(profile)) {
            log.info("[TTS运行时] {} 服务已可用，跳过自动启动: baseUrl={}", profile.name(), profile.baseUrl());
            return;
        }
        startProcess(profile);
    }

    /**
     * Spring 关闭时停止由当前进程拉起的 TTS sidecar。
     */
    @PreDestroy
    public void stopIfStarted() {
        stopProcess();
    }

    /**
     * 定期检查 sidecar 是否异常退出，必要时按配置重启有限次数。
     */
    @Scheduled(fixedDelay = 30_000)
    public void healthCheck() {
        RuntimeProfile profile = activeRuntimeProfile();
        if (profile == null || process == null || process.isAlive()) {
            return;
        }

        int exitCode = process.exitValue();
        int maxRestartAttempts = Math.max(0, profile.runtime().getMaxRestartAttempts());
        log.warn("[TTS运行时] {} 进程已退出: exitCode={}, restartCount={}, maxRestartAttempts={}",
                profile.name(), exitCode, restartCount, maxRestartAttempts);
        if (restartCount < maxRestartAttempts) {
            startProcess(profile);
        } else {
            log.error("[TTS运行时] {} 达到最大重启次数，放弃重启", profile.name());
        }
    }

    /**
     * 当前是否存在由 Java 拉起并仍在运行的 sidecar 进程。
     *
     * @return true 表示进程仍存活
     */
    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    /**
     * 判断由 Java 管理的本地 TTS 服务是否已经可接收请求。
     *
     * @return true 表示当前配置需要本地 sidecar，且健康检查端点已返回成功
     */
    public boolean isManagedRuntimeReady() {
        RuntimeProfile profile = activeRuntimeProfile();
        return profile != null && isServiceHealthy(profile);
    }

    /**
     * 判断合成前是否需要等待本地 sidecar 就绪。
     *
     * @return true 表示当前配置由 Java 负责拉起外部 TTS 进程
     */
    public boolean shouldWaitForManagedRuntime() {
        return activeRuntimeProfile() != null;
    }

    /**
     * 解析当前 provider 对应的 runtime profile。
     *
     * @return 需要 Java 管理时返回 profile；否则返回 null
     */
    private RuntimeProfile activeRuntimeProfile() {
        if (!config.enabled()) {
            return null;
        }
        if (config.gptSoVitsProviderEnabled() && config.getRuntime().isAutoStartEnabled()) {
            return new RuntimeProfile("GPT-SoVITS", config.getGptSoVits().getBaseUrl(), config.getRuntime());
        }
        TtsConfig.RuntimeConfig voxRuntime = config.getVoxCpm2().getRuntime();
        if (config.voxCpmProviderEnabled() && voxRuntime != null && voxRuntime.isAutoStartEnabled()) {
            return new RuntimeProfile("VoxCPM2", config.getVoxCpm2().getBaseUrl(), voxRuntime);
        }
        return null;
    }

    /**
     * 启动当前 provider 的 TTS sidecar 进程。
     *
     * @param profile 当前 provider 的 runtime profile
     */
    private void startProcess(RuntimeProfile profile) {
        Path workingDirectory = resolveWorkingDirectory(profile);
        if (workingDirectory == null) {
            return;
        }
        List<String> missingFiles = missingRequiredFiles(workingDirectory, profile.runtime());
        if (!missingFiles.isEmpty()) {
            log.warn("[TTS运行时] {} 必要文件缺失，跳过自动启动: workingDirectory={}, missing={}",
                    profile.name(), workingDirectory, missingFiles);
            return;
        }

        List<String> command = runtimeCommand(profile.runtime());
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workingDirectory.toFile());
            applyRuntimeEnvironment(builder, workingDirectory, profile.runtime());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(resolveLogFile(workingDirectory, profile.runtime()).toFile()));
            boolean isRestart = process != null;
            process = builder.start();
            if (isRestart) {
                restartCount++;
            }
            log.info("[TTS运行时] {} 进程已启动: listen={}, workingDirectory={}, logFile={}, restartCount={}",
                    profile.name(), listenAddress(profile.runtime()), workingDirectory,
                    resolveLogFile(workingDirectory, profile.runtime()), restartCount);
            sleepAfterStartup(profile);
        } catch (IOException e) {
            log.warn("[TTS运行时] {} 进程启动失败: command={}, reason={}", profile.name(), command, e.getMessage());
        }
    }

    /**
     * 停止当前由 Java 拉起的 TTS sidecar 进程。
     */
    private void stopProcess() {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        log.info("[TTS运行时] TTS sidecar 进程已请求停止");
    }

    /**
     * 构建 TTS sidecar 启动命令。
     * <p>
     * 完整 startup-command 优先；没有配置时再退回 Python 脚本 + -a/-p/-c 的兼容启动方式。
     *
     * @param runtime 当前 provider 的 runtime 配置
     * @return 拆分后的命令参数
     */
    private List<String> runtimeCommand(TtsConfig.RuntimeConfig runtime) {
        if (runtime.getStartupCommand() != null && !runtime.getStartupCommand().isEmpty()) {
            return new ArrayList<>(runtime.getStartupCommand());
        }

        List<String> command = new ArrayList<>();
        if (runtime.getLauncherCommand() != null && !runtime.getLauncherCommand().isEmpty()) {
            command.addAll(runtime.getLauncherCommand());
        } else if (hasText(runtime.getPythonExecutable())) {
            command.add(runtime.getPythonExecutable().trim());
        } else {
            command.add("python");
        }

        command.add(hasText(runtime.getApiServerScript()) ? runtime.getApiServerScript().trim() : "api_v2.py");
        command.add("-a");
        command.add(hasText(runtime.getBindAddress()) ? runtime.getBindAddress().trim() : "127.0.0.1");
        command.add("-p");
        command.add(String.valueOf(runtime.getPort()));
        if (hasText(runtime.getTtsConfigPath())) {
            command.add("-c");
            command.add(runtime.getTtsConfigPath().trim());
        }
        if (runtime.getExtraArgs() != null) {
            command.addAll(runtime.getExtraArgs());
        }
        return command;
    }

    /**
     * 为外部进程补充运行环境变量。
     *
     * @param builder          进程构造器
     * @param workingDirectory TTS 工作目录
     * @param runtime          当前 provider 的 runtime 配置
     */
    private void applyRuntimeEnvironment(ProcessBuilder builder, Path workingDirectory, TtsConfig.RuntimeConfig runtime) {
        builder.environment().putIfAbsent("UV_CACHE_DIR", workingDirectory.resolve(".uv-cache").toString());
        Map<String, String> environment = runtime.getEnvironment();
        if (environment == null || environment.isEmpty()) {
            return;
        }
        environment.forEach((key, value) -> {
            if (hasText(key) && value != null) {
                builder.environment().put(key.trim(), value);
            }
        });
    }

    /**
     * 检查启动 TTS sidecar 所需的入口文件和推理配置是否存在。
     *
     * @param workingDirectory TTS 工作目录
     * @param runtime          当前 provider 的 runtime 配置
     * @return 缺失文件列表
     */
    private List<String> missingRequiredFiles(Path workingDirectory, TtsConfig.RuntimeConfig runtime) {
        List<String> missing = new ArrayList<>();
        if (runtime.getStartupCommand() != null && !runtime.getStartupCommand().isEmpty()) {
            requireStartupCommandFiles(workingDirectory, runtime.getStartupCommand(), missing);
            return missing;
        }
        requireFile(workingDirectory, runtime.getApiServerScript(), missing);
        if (hasText(runtime.getTtsConfigPath())) {
            requireFile(workingDirectory, runtime.getTtsConfigPath(), missing);
        }
        return missing;
    }

    /**
     * 解析 TTS sidecar 工作目录。
     * <p>
     * 开发时可能从仓库根目录启动，也可能从 backend 目录启动，所以这里兼容两种相对路径。
     *
     * @return 存在的工作目录；不存在时返回 null
     */
    private Path resolveWorkingDirectory(RuntimeProfile profile) {
        String configured = profile.runtime().getWorkingDirectory();
        if (!hasText(configured)) {
            log.warn("[TTS运行时] 未配置 {} 工作目录", profile.name());
            return null;
        }

        Path direct = Path.of(configured).toAbsolutePath().normalize();
        if (Files.isDirectory(direct)) {
            return direct;
        }

        Path underBackend = Path.of("backend").resolve(configured).toAbsolutePath().normalize();
        if (Files.isDirectory(underBackend)) {
            return underBackend;
        }

        log.info("[TTS运行时] {} 工作目录不存在，跳过自动启动: configured={}, direct={}, backendFallback={}",
                profile.name(), configured, direct, underBackend);
        return null;
    }

    /**
     * 解析 sidecar stdout/stderr 日志文件。
     *
     * @param workingDirectory TTS 工作目录
     * @param runtime          当前 provider 的 runtime 配置
     * @return 日志文件路径
     * @throws IOException 创建日志目录失败
     */
    private Path resolveLogFile(Path workingDirectory, TtsConfig.RuntimeConfig runtime) throws IOException {
        String logFilePath = runtime.getLogFilePath();
        Path logFile = hasText(logFilePath)
                ? workingDirectory.resolve(logFilePath).normalize()
                : workingDirectory.resolve("gpt-sovits-server.log").normalize();
        Path parent = logFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return logFile;
    }

    /**
     * 检查当前 provider 的外部 TTS 服务是否已经可用。
     *
     * @return true 表示健康检查端点返回 2xx
     */
    public boolean isServiceHealthy() {
        RuntimeProfile profile = activeRuntimeProfile();
        return profile != null && isServiceHealthy(profile);
    }

    /**
     * 检查指定 runtime profile 的服务是否已经可用。
     *
     * @param profile 当前 provider 的 runtime profile
     * @return true 表示健康检查端点返回 2xx
     */
    private boolean isServiceHealthy(RuntimeProfile profile) {
        try {
            if (!hasText(profile.baseUrl())) {
                return false;
            }
            URI healthUri = URI.create(profile.baseUrl().replaceAll("/+$", "") + healthPath(profile.runtime()));
            HttpRequest request = HttpRequest.newBuilder(healthUri)
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 启动后短暂等待，给 Python 进程留出初始化时间。
     *
     * @param profile 当前 provider 的 runtime profile
     */
    private void sleepAfterStartup(RuntimeProfile profile) {
        long waitMs = Math.max(0L, profile.runtime().getStartupWaitMs());
        if (waitMs <= 0) {
            return;
        }
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[TTS运行时] 等待 {} 启动时被中断", profile.name());
        }
    }

    private String healthPath(TtsConfig.RuntimeConfig runtime) {
        String configured = runtime.getHealthPath();
        if (!hasText(configured)) {
            return "/docs";
        }
        return configured.startsWith("/") ? configured : "/" + configured;
    }

    private String listenAddress(TtsConfig.RuntimeConfig runtime) {
        String host = hasText(runtime.getBindAddress()) ? runtime.getBindAddress().trim() : "127.0.0.1";
        return host + ":" + runtime.getPort();
    }

    private void requireFile(Path workingDirectory, String relativePath, List<String> missing) {
        if (!hasText(relativePath)) {
            return;
        }
        if (!Files.isRegularFile(workingDirectory.resolve(relativePath).normalize())) {
            missing.add(relativePath);
        }
    }

    /**
     * 检查整合包启动命令里显式引用的脚本是否存在。
     * <p>
     * 例如 startup-command 为 cmd /c go-webui.bat 时，只检查 go-webui.bat；cmd、/c 等系统命令不检查。
     *
     * @param workingDirectory TTS sidecar 工作目录
     * @param startupCommand   完整启动命令
     * @param missing          缺失文件列表
     */
    private void requireStartupCommandFiles(Path workingDirectory, List<String> startupCommand, List<String> missing) {
        for (String part : startupCommand) {
            if (!looksLikeStartupScript(part)) {
                continue;
            }
            Path scriptPath = Path.of(part);
            Path resolved = scriptPath.isAbsolute()
                    ? scriptPath.normalize()
                    : workingDirectory.resolve(part).normalize();
            if (!Files.isRegularFile(resolved)) {
                missing.add(part);
            }
        }
    }

    private boolean looksLikeStartupScript(String value) {
        if (!hasText(value)) {
            return false;
        }
        String normalized = value.trim().toLowerCase();
        return normalized.endsWith(".bat")
                || normalized.endsWith(".cmd")
                || normalized.endsWith(".ps1")
                || normalized.endsWith(".exe")
                || normalized.endsWith(".py");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 当前 provider 的 sidecar 运行时信息。
     *
     * @param name    provider 显示名
     * @param baseUrl 健康检查基础地址
     * @param runtime 启动配置
     */
    private record RuntimeProfile(String name, String baseUrl, TtsConfig.RuntimeConfig runtime) {
    }
}
