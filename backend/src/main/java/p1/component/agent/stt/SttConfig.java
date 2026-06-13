package p1.component.agent.stt;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * STT 工程级默认配置。
 * <p>
 * 默认 ASR 引擎是 sherpa-onnx Qwen3-ASR ONNX。缺失 runtime 或模型时应由 doctor
 * 和 sidecar 日志明确报错，不隐式切换到其他 ASR 实现。
 */
@Component
public class SttConfig {

    public static final String ASR_V2_PROTOCOL = "asr-v2";
    public static final String SHERPA_QWEN_ENGINE = "sherpa-qwen-onnx";
    public static final String DEFAULT_ENGINE = SHERPA_QWEN_ENGINE;

    private final Environment springEnvironment;

    public SttConfig() {
        this(null);
    }

    @Autowired
    public SttConfig(Environment springEnvironment) {
        this.springEnvironment = springEnvironment;
    }
    public int serverPort() {
        return 6006;
    }

    public int enginePort(String engine) {
        if (!supportedEngine(engine)) {
            throw new IllegalArgumentException("Unsupported STT engine: " + normalizeEngine(engine));
        }
        return 6006;
    }

    public String sttEngine() {
        String value = firstConfiguredValue("stt.engine", "STT_ENGINE", "stt.engine");
        return normalizeEngine(value);
    }
    public String normalizeEngine(String engine) {
        String normalized = engine == null ? "" : engine.trim().toLowerCase().replace('_', '-');
        return switch (normalized) {
            case "" -> DEFAULT_ENGINE;
            case "sherpa-qwen-onnx", "sherpa-qwen", "sherpa" -> SHERPA_QWEN_ENGINE;
            default -> normalized;
        };
    }

    public boolean supportedEngine(String engine) {
        String normalized = engine == null ? "" : engine.trim().toLowerCase().replace('_', '-');
        return switch (normalized) {
            case "", "sherpa-qwen-onnx", "sherpa-qwen", "sherpa" -> true;
            default -> false;
        };
    }
    public String projectRuntimePythonPath() {
        Path cwd = workingDirectory();
        Path backendRuntime = cwd.resolve(Path.of("runtime", "python", ".venv", "Scripts", "python.exe"));
        Path repoRuntime = cwd.resolve(Path.of("backend", "runtime", "python", ".venv", "Scripts", "python.exe"));
        if (Files.isRegularFile(backendRuntime)) {
            return backendRuntime.toAbsolutePath().normalize().toString();
        }
        if (Files.isRegularFile(repoRuntime)) {
            return repoRuntime.toAbsolutePath().normalize().toString();
        }
        Path defaultPath = looksLikeBackendWorkingDirectory(cwd) ? backendRuntime : repoRuntime;
        return defaultPath.toAbsolutePath().normalize().toString();
    }

    public String pythonBootstrapHint() {
        Path cwd = workingDirectory();
        Path backendScript = cwd.resolve(Path.of("tools", "python", "bootstrap.ps1"));
        Path repoScript = cwd.resolve(Path.of("backend", "tools", "python", "bootstrap.ps1"));
        Path script = Files.isRegularFile(backendScript) ? backendScript : repoScript;
        return "powershell -ExecutionPolicy Bypass -File " + script.toAbsolutePath().normalize();
    }

    public String externalAsrBundlePath() {
        String fromProperty = System.getProperty("stt.asr.bundle-dir");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return normalizePath(fromProperty.trim());
        }
        String fromSttEnv = environment("STT_ASR_BUNDLE_DIR");
        if (fromSttEnv != null && !fromSttEnv.isBlank()) {
            return normalizePath(fromSttEnv.trim());
        }
        String fromProjectEnv = environment("ARCLIGHT_ASR_BUNDLE_DIR");
        if (fromProjectEnv != null && !fromProjectEnv.isBlank()) {
            return normalizePath(fromProjectEnv.trim());
        }
        return backendRelativePath(Path.of("runtime", "asr", "custom"));
    }

    public String sherpaQwenRuntimeRootPath() {
        return backendRelativePath(Path.of("runtime", "asr", "sherpa-qwen"));
    }

    public String sherpaQwenPythonExecutable() {
        String fromProperty = System.getProperty("stt.sherpa-qwen.python.executable");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return normalizePath(fromProperty.trim());
        }
        String fromEnv = environment("STT_SHERPA_QWEN_PYTHON_EXE");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return normalizePath(fromEnv.trim());
        }
        return backendRelativePath(Path.of("runtime", "asr", "sherpa-qwen", ".venv", "Scripts", "python.exe"));
    }

    public String sherpaQwenModelDir() {
        String fromProperty = System.getProperty("stt.sherpa-qwen.model-dir");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return normalizePath(fromProperty.trim());
        }
        String fromEnv = environment("STT_SHERPA_QWEN_MODEL_DIR");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return normalizePath(fromEnv.trim());
        }
        return backendRelativePath(Path.of(
                "runtime",
                "asr",
                "1.7B"));
    }

    public String sherpaDiarizationModelDir() {
        String fromProperty = System.getProperty("stt.sherpa-qwen.diarization-model-dir");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return normalizePath(fromProperty.trim());
        }
        String fromEnv = environment("STT_SHERPA_DIARIZATION_MODEL_DIR");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return normalizePath(fromEnv.trim());
        }
        return backendRelativePath(Path.of("runtime", "asr", "sherpa-qwen", "models", "diarization"));
    }

    public int partialIntervalMs() {
        String fromProperty = System.getProperty("stt.partial-interval-ms");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return positiveIntOrDefault(fromProperty, 500);
        }
        String fromEnv = environment("STT_PARTIAL_INTERVAL_MS");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return positiveIntOrDefault(fromEnv, 500);
        }
        String fromConfig = configProperty("stt.partial-interval-ms");
        if (fromConfig != null && !fromConfig.isBlank()) {
            return positiveIntOrDefault(fromConfig, 500);
        }
        return 500;
    }

    public int maxSegmentMs() {
        String fromProperty = System.getProperty("stt.max-segment-ms");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return positiveIntOrDefault(fromProperty, 3000);
        }
        String fromEnv = environment("STT_MAX_SEGMENT_MS");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return positiveIntOrDefault(fromEnv, 3000);
        }
        String fromConfig = configProperty("stt.max-segment-ms");
        if (fromConfig != null && !fromConfig.isBlank()) {
            return positiveIntOrDefault(fromConfig, 3000);
        }
        return 3000;
    }

    public String sherpaQwenHotwords() {
        String fromProperty = System.getProperty("stt.sherpa-qwen.hotwords");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty.trim();
        }
        String fromEnv = environment("STT_SHERPA_QWEN_HOTWORDS");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        String fromConfig = configProperty("stt.sherpa-qwen.hotwords");
        return fromConfig == null ? "" : fromConfig.trim();
    }

    public String sherpaQwenHotwordsFilePath() {
        String fromProperty = System.getProperty("stt.sherpa-qwen.hotwords-file");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return normalizePath(fromProperty.trim());
        }
        String fromEnv = environment("STT_SHERPA_QWEN_HOTWORDS_FILE");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return normalizePath(fromEnv.trim());
        }
        String fromConfig = configProperty("stt.sherpa-qwen.hotwords-file");
        if (fromConfig != null && !fromConfig.isBlank()) {
            return normalizePath(fromConfig.trim());
        }

        Path workspaceHotwords = workingDirectory().resolve("词表.txt").toAbsolutePath().normalize();
        if (Files.isRegularFile(workspaceHotwords)) {
            return workspaceHotwords.toString();
        }
        Path repoHotwords = workingDirectory().resolve(Path.of("backend", "tools", "asr", "hotwords-game-zh.txt"))
                .toAbsolutePath().normalize();
        if (Files.isRegularFile(repoHotwords)) {
            return repoHotwords.toString();
        }
        return backendRelativePath(Path.of("tools", "asr", "hotwords-game-zh.txt"));
    }

    public int sherpaQwenMaxHotwords() {
        String fromProperty = System.getProperty("stt.sherpa-qwen.max-hotwords");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return positiveIntOrDefault(fromProperty, 300);
        }
        String fromEnv = environment("STT_SHERPA_QWEN_MAX_HOTWORDS");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return positiveIntOrDefault(fromEnv, 300);
        }
        String fromConfig = configProperty("stt.sherpa-qwen.max-hotwords");
        if (fromConfig != null && !fromConfig.isBlank()) {
            return positiveIntOrDefault(fromConfig, 300);
        }
        return 300;
    }

    public String sherpaQwenSidecarScriptPath() {
        return backendRelativePath(Path.of("tools", "asr", "sherpa_qwen_sidecar.py"));
    }

    public String sherpaQwenBootstrapHint() {
        return "powershell -ExecutionPolicy Bypass -File "
                + backendRelativePath(Path.of("tools", "asr", "bootstrap-sherpa-qwen.ps1"));
    }

    public String sherpaQwenLogFilePath() {
        return backendRelativePath(Path.of("runtime", "asr", "sherpa-qwen", "sherpa-qwen-sidecar.log"));
    }

    public boolean sherpaQwenModelAvailable() {
        return Files.isRegularFile(sherpaQwenConvFrontendPath())
                && Files.isRegularFile(sherpaQwenEncoderPath())
                && Files.isRegularFile(sherpaQwenDecoderPath())
                && Files.isDirectory(sherpaQwenTokenizerPath())
                && Files.isRegularFile(sherpaQwenTokenizerPath().resolve("vocab.json"))
                && Files.isRegularFile(sherpaQwenTokenizerPath().resolve("merges.txt"))
                && Files.isRegularFile(sherpaQwenTokenizerPath().resolve("tokenizer_config.json"));
    }

    public Path sherpaQwenConvFrontendPath() {
        return Path.of(sherpaQwenModelDir(), "conv_frontend.onnx").toAbsolutePath().normalize();
    }

    public Path sherpaQwenEncoderPath() {
        return existingModelFile(sherpaQwenModelDir(), "encoder.int8.onnx", "encoder.onnx");
    }

    public Path sherpaQwenDecoderPath() {
        return existingModelFile(sherpaQwenModelDir(), "decoder.int8.onnx", "decoder.onnx");
    }

    public Path sherpaQwenTokenizerPath() {
        return Path.of(sherpaQwenModelDir(), "tokenizer").toAbsolutePath().normalize();
    }

    public boolean sherpaDiarizationModelAvailable() {
        return Files.isRegularFile(sherpaSegmentationModelPath())
                && Files.isRegularFile(sherpaSpeakerEmbeddingModelPath());
    }

    public Path sherpaSegmentationModelPath() {
        List<Path> candidates = List.of(
                Path.of(sherpaDiarizationModelDir(), "sherpa-onnx-pyannote-segmentation-3-0", "model.int8.onnx"),
                Path.of(sherpaDiarizationModelDir(), "sherpa-onnx-pyannote-segmentation-3-0", "model.onnx"),
                Path.of(sherpaDiarizationModelDir(), "segmentation", "model.int8.onnx"),
                Path.of(sherpaDiarizationModelDir(), "segmentation", "model.onnx")
        );
        return firstExisting(candidates);
    }

    public Path sherpaSpeakerEmbeddingModelPath() {
        List<Path> candidates = List.of(
                Path.of(sherpaDiarizationModelDir(), "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"),
                Path.of(sherpaDiarizationModelDir(), "embedding", "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"),
                Path.of(sherpaDiarizationModelDir(), "embedding.onnx")
        );
        return firstExisting(candidates);
    }

    public boolean runtimeAutoStartEnabled() {
        return true;
    }

    public long runtimeStartupWaitMs() {
        return 500;
    }

    public List<String> sherpaQwenRuntimeCommand() {
        List<String> command = new ArrayList<>();
        command.add(sherpaQwenPythonExecutable());
        command.add(sherpaQwenSidecarScriptPath());
        command.add("--host");
        command.add("127.0.0.1");
        command.add("--port");
        command.add(String.valueOf(enginePort(SHERPA_QWEN_ENGINE)));
        command.add("--qwen-model-dir");
        command.add(sherpaQwenModelDir());
        command.add("--diarization-model-dir");
        command.add(sherpaDiarizationModelDir());
        command.add("--partial-interval-ms");
        command.add(String.valueOf(partialIntervalMs()));
        command.add("--max-segment-ms");
        command.add(String.valueOf(maxSegmentMs()));
        command.add("--provider");
        command.add("cpu");
        command.add("--num-threads");
        command.add("3");
        String hotwords = sherpaQwenHotwords();
        if (!hotwords.isBlank()) {
            command.add("--hotwords");
            command.add(hotwords);
        }
        String hotwordsFile = sherpaQwenHotwordsFilePath();
        if (!hotwordsFile.isBlank()) {
            command.add("--hotwords-file");
            command.add(hotwordsFile);
        }
        command.add("--max-hotwords");
        command.add(String.valueOf(sherpaQwenMaxHotwords()));
        return command;
    }

    Path workingDirectory() {
        return Path.of("").toAbsolutePath().normalize();
    }

    String environment(String name) {
        return System.getenv(name);
    }

    String configProperty(String name) {
        return springEnvironment == null ? null : springEnvironment.getProperty(name);
    }

    private String firstConfiguredValue(String propertyName, String environmentName, String configName) {
        String fromProperty = System.getProperty(propertyName);
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty.trim();
        }
        String fromEnv = environment(environmentName);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        String fromConfig = configProperty(configName);
        return fromConfig == null ? null : fromConfig.trim();
    }
    private Path existingModelFile(String directory, String preferred, String fallback) {
        Path preferredPath = Path.of(directory, preferred).toAbsolutePath().normalize();
        if (Files.isRegularFile(preferredPath)) {
            return preferredPath;
        }
        return Path.of(directory, fallback).toAbsolutePath().normalize();
    }

    private Path firstExisting(List<Path> candidates) {
        return candidates.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .filter(Files::isRegularFile)
                .findFirst()
                .orElse(candidates.getFirst().toAbsolutePath().normalize());
    }

    private String backendRelativePath(Path relativePath) {
        Path cwd = workingDirectory();
        Path backendPath = cwd.resolve(relativePath);
        Path repoPath = cwd.resolve("backend").resolve(relativePath);
        if (Files.exists(backendPath)) {
            return backendPath.toAbsolutePath().normalize().toString();
        }
        if (Files.exists(repoPath)) {
            return repoPath.toAbsolutePath().normalize().toString();
        }
        Path defaultPath = looksLikeBackendWorkingDirectory(cwd) ? backendPath : repoPath;
        return defaultPath.toAbsolutePath().normalize().toString();
    }

    private String normalizePath(String path) {
        return Path.of(path).toAbsolutePath().normalize().toString();
    }

    private int positiveIntOrDefault(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private boolean looksLikeBackendWorkingDirectory(Path cwd) {
        return Files.isRegularFile(cwd.resolve("pom.xml")) && Files.isDirectory(cwd.resolve(Path.of("src", "main", "java")));
    }
}
