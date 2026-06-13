package p1.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import p1.component.agent.stt.SttConfig;
import p1.component.agent.tts.TtsConfig;
import p1.config.ExternalConfigDirectories;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 本地运行时依赖检查器。
 * <p>
 * Doctor 只报告文件、目录和端口状态，不启动或修改任何 sidecar 进程。
 */
@Service
public class RuntimeDoctorService {

    private static final String OK = "OK";
    private static final String WARN = "WARN";
    private static final String ERROR = "ERROR";
    private static final int PORT_TIMEOUT_MS = 250;

    private final SttConfig sttConfig;
    private final TtsConfig ttsConfig;
    private final PortProbe portProbe;
    private final Supplier<Path> workingDirectorySupplier;
    private final Supplier<Path> configDirectorySupplier;
    private final Clock clock;
    private final RuntimeProbe asrRuntimeProbe;

    @Autowired
    public RuntimeDoctorService(SttConfig sttConfig, TtsConfig ttsConfig) {
        this(sttConfig, ttsConfig, RuntimeDoctorService::isTcpPortOpen,
                () -> Path.of("").toAbsolutePath().normalize(),
                ExternalConfigDirectories::resolveConfiguredDir,
                Clock.systemDefaultZone());
    }

    RuntimeDoctorService(SttConfig sttConfig,
                         TtsConfig ttsConfig,
                         PortProbe portProbe,
                         Supplier<Path> workingDirectorySupplier,
                         Supplier<Path> configDirectorySupplier,
                         Clock clock) {
        this(sttConfig, ttsConfig, portProbe, workingDirectorySupplier, configDirectorySupplier, clock,
                RuntimeDoctorService::pythonCanImportAsrRuntime);
    }

    RuntimeDoctorService(SttConfig sttConfig,
                         TtsConfig ttsConfig,
                         PortProbe portProbe,
                         Supplier<Path> workingDirectorySupplier,
                         Supplier<Path> configDirectorySupplier,
                         Clock clock,
                         RuntimeProbe asrRuntimeProbe) {
        this.sttConfig = sttConfig;
        this.ttsConfig = ttsConfig;
        this.portProbe = portProbe;
        this.workingDirectorySupplier = workingDirectorySupplier;
        this.configDirectorySupplier = configDirectorySupplier;
        this.clock = clock;
        this.asrRuntimeProbe = asrRuntimeProbe;
    }

    public DoctorSnapshot snapshot() {
        List<DoctorCheck> checks = new ArrayList<>();
        checks.add(configDirectoryCheck());
        checks.add(projectPythonCheck());
        checks.add(sttEngineCheck());
        checks.add(sherpaSidecarSourceCheck());
        checks.add(sttPortCheck());
        checks.add(sherpaQwenModelCheck());
        checks.add(sherpaDiarizationModelCheck());
        checks.add(sherpaRuntimeCheck());
        checks.add(ttsRuntimeCheck());
        checks.add(apiContractCheck());

        String status = worstStatus(checks);
        int issueCount = (int) checks.stream()
                .filter(check -> !OK.equals(check.status()))
                .count();
        return new DoctorSnapshot(status, issueCount > 0, issueCount, Instant.now(clock).toString(), checks);
    }

    private DoctorCheck configDirectoryCheck() {
        Path configDir = configDirectorySupplier.get().toAbsolutePath().normalize();
        if (Files.isDirectory(configDir)) {
            return ok("runtime.config-dir", "外部配置目录", "外部 config 目录可用。", configDir);
        }
        return error("runtime.config-dir", "外部配置目录",
                "外部 config 目录不存在，后端无法持久化用户可编辑配置。",
                "创建目录: " + configDir, configDir);
    }

    private DoctorCheck projectPythonCheck() {
        Path python = Path.of(sttConfig.projectRuntimePythonPath());
        if (Files.isRegularFile(python)) {
            return ok("runtime.python", "项目 Python runtime", "项目级轻 Python runtime 可用。", python);
        }
        return warn("runtime.python", "项目 Python runtime",
                "项目级轻 Python runtime 缺失；这不影响 sherpa-qwen ASR，但会影响其他轻量 Python 工具。",
                sttConfig.pythonBootstrapHint(), python);
    }
    private DoctorCheck sttEngineCheck() {
        String engine = sttConfig.sttEngine();
        if (SttConfig.DEFAULT_ENGINE.equals(engine)) {
            return ok("stt.engine", "STT engine", "Active STT engine is sherpa-qwen-onnx.", null);
        }
        return error("stt.engine", "STT engine",
                "Unsupported STT engine: " + engine,
                "Set STT_ENGINE=" + SttConfig.DEFAULT_ENGINE, null);
    }
    private DoctorCheck sttPortCheck() {
        if (portOpen("127.0.0.1", sttConfig.serverPort())) {
            return ok("stt.sidecar-port", "ASR sidecar 端口",
                    "ASR WebSocket sidecar 正在监听 127.0.0.1:" + sttConfig.serverPort() + "。", null);
        }
        return warn("stt.sidecar-port", "ASR sidecar 端口",
                "当前没有 ASR WebSocket sidecar 监听 127.0.0.1:" + sttConfig.serverPort() + "。",
                "启动后端会尝试拉起 sherpa-qwen sidecar；若失败请查看 " + sttConfig.sherpaQwenLogFilePath(), null);
    }

    private DoctorCheck sherpaSidecarSourceCheck() {
        Path script = Path.of(sttConfig.sherpaQwenSidecarScriptPath());
        if (Files.isRegularFile(script)) {
            return ok("stt.sherpa-qwen-sidecar-entry", "sherpa-qwen sidecar 入口",
                    "sherpa-qwen sidecar 启动脚本存在。", script);
        }
        return error("stt.sherpa-qwen-sidecar-entry", "sherpa-qwen sidecar 入口",
                "缺少 sherpa-qwen sidecar 启动脚本，语音识别不可用。",
                "恢复文件: " + script, script);
    }

    private DoctorCheck sherpaQwenModelCheck() {
        Path modelDir = Path.of(sttConfig.sherpaQwenModelDir());
        if (sttConfig.sherpaQwenModelAvailable()) {
            return ok("stt.sherpa-qwen-model", "Qwen3-ASR ONNX 模型",
                    "Qwen3-ASR ONNX 模型文件存在。", modelDir);
        }
        return error("stt.sherpa-qwen-model", "Qwen3-ASR ONNX 模型",
                "缺少 Qwen3-ASR ONNX 模型。需要 conv_frontend.onnx、encoder、decoder 和 tokenizer 目录。",
                sttConfig.sherpaQwenBootstrapHint(), modelDir);
    }

    private DoctorCheck sherpaDiarizationModelCheck() {
        Path modelDir = Path.of(sttConfig.sherpaDiarizationModelDir());
        if (sttConfig.sherpaDiarizationModelAvailable()) {
            return ok("stt.sherpa-diarization-model", "说话人标注模型",
                    "speaker segmentation 和 speaker embedding 模型存在。", modelDir);
        }
        return error("stt.sherpa-diarization-model", "说话人标注模型",
                "缺少 diarization 模型。需要 pyannote segmentation model 和 3D-Speaker embedding model。",
                sttConfig.sherpaQwenBootstrapHint(), modelDir);
    }

    private DoctorCheck sherpaRuntimeCheck() {
        Path python = Path.of(sttConfig.sherpaQwenPythonExecutable());
        if (!Files.isRegularFile(python)) {
            return error("stt.sherpa-qwen-runtime", "sherpa-qwen runtime",
                    "sherpa-qwen Python runtime 缺失。",
                    sttConfig.sherpaQwenBootstrapHint(), python);
        }
        if (asrRuntimeProbe.check(SttConfig.SHERPA_QWEN_ENGINE, python)) {
            return ok("stt.sherpa-qwen-runtime", "sherpa-qwen runtime",
                    "sherpa_onnx、numpy 和 websockets imports 可用。", python);
        }
        return error("stt.sherpa-qwen-runtime", "sherpa-qwen runtime",
                "Python 存在，但 sherpa_onnx/numpy/websockets imports 失败。",
                sttConfig.sherpaQwenBootstrapHint(), python);
    }

    private DoctorCheck ttsRuntimeCheck() {
        if (!ttsConfig.enabled()) {
            return ok("tts.runtime", "TTS runtime", "TTS 已禁用，跳过本地 TTS sidecar 检查。", null);
        }

        ActiveTtsRuntime runtime = activeTtsRuntime();
        if (runtime == null) {
            return warn("tts.runtime", "TTS runtime",
                    "当前 TTS provider 不由后端自动管理，doctor 只检查配置有效性。",
                    "确认外部 TTS 服务地址: " + ttsConfig.providerBaseUrl(), null);
        }

        Path workingDirectory = resolveProjectPath(runtime.config().getWorkingDirectory());
        boolean portOpen = portOpen(runtime.config().getBindAddress(), runtime.config().getPort());
        if (Files.isDirectory(workingDirectory) && portOpen) {
            return ok("tts.runtime", runtime.name() + " runtime",
                    "TTS 工作目录存在，健康端口正在监听。", workingDirectory);
        }
        if (Files.isDirectory(workingDirectory)) {
            return warn("tts.runtime", runtime.name() + " runtime",
                    "TTS 工作目录存在，但健康端口未监听 " + runtime.config().getBindAddress()
                            + ":" + runtime.config().getPort() + "。",
                    "启动后端会按当前 TTS runtime 配置尝试拉起 sidecar。", workingDirectory);
        }
        return warn("tts.runtime", runtime.name() + " runtime",
                "TTS 工作目录不存在，当前 provider 的语音合成不可用。",
                "准备目录: " + workingDirectory, workingDirectory);
    }

    private DoctorCheck apiContractCheck() {
        Path contract = resolveProjectPath("docs/contracts/arclight-api.openapi.json");
        if (Files.isRegularFile(contract)) {
            return ok("contract.openapi", "前后端 API 契约", "OpenAPI 契约文件存在。", contract);
        }
        return error("contract.openapi", "前后端 API 契约",
                "缺少 OpenAPI 契约文件，前后端接口变更没有机器可读基准。",
                "创建 docs/contracts/arclight-api.openapi.json", contract);
    }
    private static boolean pythonCanImportAsrRuntime(String engine, Path python) {
        String code = importProbeCode("sherpa_onnx", "numpy", "websockets");
        try {
            Process process = new ProcessBuilder(python.toString(), "-c", code)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(20, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String importProbeCode(String... modules) {
        String quoted = String.join(",", List.of(modules).stream()
                .map(module -> "'" + module + "'")
                .toList());
        return """
                import importlib.util
                missing = [name for name in (%s) if importlib.util.find_spec(name) is None]
                raise SystemExit(1 if missing else 0)
                """.formatted(quoted);
    }
    private ActiveTtsRuntime activeTtsRuntime() {
        if (ttsConfig.gptSoVitsProviderEnabled() && ttsConfig.getRuntime() != null
                && ttsConfig.getRuntime().isAutoStartEnabled()) {
            return new ActiveTtsRuntime("GPT-SoVITS", ttsConfig.getRuntime());
        }
        return null;
    }

    private boolean portOpen(String host, int port) {
        try {
            return portProbe.isOpen(host == null || host.isBlank() ? "127.0.0.1" : host.trim(), port, PORT_TIMEOUT_MS);
        } catch (Exception ignored) {
            return false;
        }
    }

    private Path resolveProjectPath(String configuredPath) {
        Path cwd = workingDirectorySupplier.get().toAbsolutePath().normalize();
        Path configured = Path.of(configuredPath == null ? "" : configuredPath.trim());
        if (configured.isAbsolute()) {
            return configured.normalize();
        }

        Path direct = cwd.resolve(configured).normalize();
        if (Files.exists(direct)) {
            return direct;
        }

        Path repoRelative = cwd.resolve("backend").resolve(configured).normalize();
        if (Files.exists(repoRelative)) {
            return repoRelative;
        }

        Path parentRelative = cwd.resolve("..").resolve(configured).normalize();
        if (Files.exists(parentRelative)) {
            return parentRelative;
        }

        return looksLikeBackendWorkingDirectory(cwd) ? direct : repoRelative;
    }

    private boolean looksLikeBackendWorkingDirectory(Path cwd) {
        return Files.isRegularFile(cwd.resolve("pom.xml")) && Files.isDirectory(cwd.resolve(Path.of("src", "main", "java")));
    }

    private String worstStatus(List<DoctorCheck> checks) {
        return checks.stream()
                .map(DoctorCheck::status)
                .max(Comparator.comparingInt(this::severityRank))
                .orElse(OK);
    }

    private int severityRank(String status) {
        return switch (status) {
            case ERROR -> 2;
            case WARN -> 1;
            default -> 0;
        };
    }

    private DoctorCheck ok(String id, String label, String detail, Path path) {
        return new DoctorCheck(id, label, OK, detail, "", path == null ? "" : path.toString());
    }

    private DoctorCheck warn(String id, String label, String detail, String action, Path path) {
        return new DoctorCheck(id, label, WARN, detail, action, path == null ? "" : path.toString());
    }

    private DoctorCheck error(String id, String label, String detail, String action, Path path) {
        return new DoctorCheck(id, label, ERROR, detail, action, path == null ? "" : path.toString());
    }

    private static boolean isTcpPortOpen(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public record DoctorSnapshot(String status,
                                 boolean hasIssues,
                                 int issueCount,
                                 String checkedAt,
                                 List<DoctorCheck> checks) {
    }

    public record DoctorCheck(String id,
                              String label,
                              String status,
                              String detail,
                              String action,
                              String path) {
    }

    private record ActiveTtsRuntime(String name, TtsConfig.RuntimeConfig config) {
    }

    @FunctionalInterface
    interface PortProbe {
        boolean isOpen(String host, int port, int timeoutMs);
    }

    @FunctionalInterface
    interface RuntimeProbe {
        boolean check(String engine, Path python);
    }
}
