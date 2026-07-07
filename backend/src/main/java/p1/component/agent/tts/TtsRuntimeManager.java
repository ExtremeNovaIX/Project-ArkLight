package p1.component.agent.tts;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.CustomLog;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import p1.infrastructure.logging.LogDomain;
import p1.infrastructure.logging.LogOutcome;

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
@CustomLog
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
            log.info(LogDomain.TTS, "runtime.ready", LogOutcome.SUCCEEDED, fields(profile, "reason", "already_available", "baseUrl", profile.baseUrl()));
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
        log.warn(LogDomain.TTS, "runtime.exited", LogOutcome.DEGRADED, fields(profile, "exitCode", exitCode, "restartCount", restartCount, "maxRestartAttempts", maxRestartAttempts));
        if (restartCount < maxRestartAttempts) {
            startProcess(profile);
        } else {
            log.error(LogDomain.TTS, "runtime.unavailable", LogOutcome.FATAL, fields(profile, "reason", "max_restart_attempts", "restartCount", restartCount, "maxRestartAttempts", maxRestartAttempts));
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
        return null;
    }

    /**
     * Start the current TTS provider sidecar process.
     *
     * @param profile current runtime profile
     */
    private void startProcess(RuntimeProfile profile) {
        Path workingDirectory = resolveWorkingDirectory(profile);
        if (workingDirectory == null) {
            return;
        }
        List<String> missingFiles = missingRequiredFiles(workingDirectory, profile.runtime());
        if (!missingFiles.isEmpty()) {
            log.warn(LogDomain.TTS, "runtime.unavailable", LogOutcome.DEGRADED, fields(profile, "reason", "missing_files", "workingDirectory", workingDirectory, "missing", missingFiles));
            return;
        }

        List<String> command = resolveExecutable(runtimeCommand(profile.runtime()), workingDirectory);
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
            log.info(LogDomain.TTS, "runtime.started", LogOutcome.SUCCEEDED, fields(profile, "listen", listenAddress(profile.runtime()), "workingDirectory", workingDirectory, "logFile", resolveLogFile(workingDirectory, profile.runtime()), "restartCount", restartCount));
            sleepAfterStartup(profile);
        } catch (IOException e) {
            log.warn(LogDomain.TTS, "runtime.unavailable", LogOutcome.DEGRADED, fields(profile, "reason", "start_failed", "command", command, "error", e.getMessage()));
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
        log.info(LogDomain.TTS, "runtime.stop_requested", LogOutcome.SUCCEEDED, Map.of());
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
     * Windows/JDK 对 {@link ProcessBuilder#directory(java.io.File)} 的处理不会可靠地解析第一个可执行文件。
     * 配置仍保持相对路径；真正启动前只把带路径分隔符的相对 executable 解析到工作目录下。
     *
     * @param command          原始启动命令
     * @param workingDirectory TTS 工作目录
     * @return executable 已解析的启动命令
     */
    List<String> resolveExecutable(List<String> command, Path workingDirectory) {
        if (command == null || command.isEmpty()) {
            return List.of();
        }
        List<String> resolved = new ArrayList<>(command);
        String executable = resolved.getFirst();
        if (!hasText(executable) || !containsPathSeparator(executable)) {
            return resolved;
        }
        Path executablePath = Path.of(executable);
        if (!executablePath.isAbsolute()) {
            resolved.set(0, workingDirectory.resolve(executablePath).normalize().toString());
        }
        return resolved;
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
            log.warn(LogDomain.TTS, "runtime.unavailable", LogOutcome.DEGRADED, fields(profile, "reason", "working_directory_not_configured"));
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

        log.info(LogDomain.TTS, "runtime.unavailable", LogOutcome.SKIPPED, fields(profile, "reason", "working_directory_missing", "configured", configured, "direct", direct, "backendFallback", underBackend));
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
            log.warn(LogDomain.TTS, "runtime.unavailable", LogOutcome.DEGRADED, fields(profile, "reason", "startup_wait_interrupted"));
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

    private boolean containsPathSeparator(String value) {
        return value.indexOf('/') >= 0 || value.indexOf('\\') >= 0;
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
    private Map<String, Object> fields(RuntimeProfile profile, Object... keyValues) {
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("provider", profile.name());
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            fields.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return fields;
    }
    private record RuntimeProfile(String name, String baseUrl, TtsConfig.RuntimeConfig runtime) {
    }
}