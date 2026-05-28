package p1.component.agent.tts;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * TTS 工程级配置。
 * <p>
 * 项目只通过 HTTP 调用外部 TTS 服务，模型、Python 环境和权重都放在外部 runtime 目录。
 * Spring 可以按当前 provider 的 runtime 配置拉起对应 sidecar，但不会把模型或 Python 依赖打包进 Java 后端。
 */
@Data
@Component
@ConfigurationProperties(prefix = "tts")
public class TtsConfig {

    private boolean enabled = false;
    private String provider = "gpt-sovits-http";
    private long synthesisTimeoutMs = 60_000;
    private int firstChunkMinChars = 6;
    private int firstChunkChars = 12;
    private int maxChunkChars = 40;
    private List<Character> sentenceEndMarks = List.of('。', '！', '？', '；', '…', '.', '!', '?', ';', '\n');
    private List<Character> firstChunkEndMarks = List.of('。', '！', '？', '；', '…', '，', '、', '.', '!', '?', ';', ',', ':', '：', '\n');
    private GptSoVitsConfig gptSoVits = new GptSoVitsConfig();
    private VoxCpm2Config voxCpm2 = new VoxCpm2Config();
    private RuntimeConfig runtime = new RuntimeConfig();

    /**
     * 是否允许 TTS 链路参与 RP 发言。
     *
     * @return true 表示尝试使用外部 TTS provider
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * 单次短句合成的超时时间。
     *
     * @return 超时时长
     */
    public Duration synthesisTimeout() {
        return Duration.ofMillis(Math.max(1000, synthesisTimeoutMs));
    }

    /**
     * TTS 分段的目标字符数，达到后等待最近句末标点切段。
     *
     * @return 目标字符数
     */
    public int maxChunkChars() {
        return Math.max(1, maxChunkChars);
    }

    /**
     * Target visible character count for the first TTS chunk.
     *
     * @return first chunk target character count
     */
    public int firstChunkChars() {
        return Math.max(1, firstChunkChars);
    }

    public int firstChunkMinChars() {
        return Math.max(1, firstChunkMinChars);
    }

    public List<Character> firstChunkEndMarks() {
        return firstChunkEndMarks == null || firstChunkEndMarks.isEmpty()
                ? List.of('。', '！', '？', '；', '…', '，', '、', '.', '!', '?', ';', ',', ':', '：', '\n')
                : firstChunkEndMarks;
    }

    /**
     * 触发短句结束的标点。
     *
     * @return 标点列表
     */
    public List<Character> sentenceEndMarks() {
        return sentenceEndMarks == null || sentenceEndMarks.isEmpty()
                ? List.of('。', '！', '？', '；', '…', '.', '!', '?', ';', '\n')
                : sentenceEndMarks;
    }

    /**
     * 当前配置是否选择 GPT-SoVITS HTTP provider。
     *
     * @return true 表示 provider 名称匹配 GPT-SoVITS
     */
    public boolean gptSoVitsProviderEnabled() {
        return GptSoVitsTtsProvider.PROVIDER_NAME.equalsIgnoreCase(provider)
                || "gpt-sovits".equalsIgnoreCase(provider)
                || "gpt-so-vits".equalsIgnoreCase(provider);
    }

    /**
     * 当前配置是否选择 OpenBMB VoxCPM2 HTTP provider。
     *
     * @return true 表示 provider 名称严格匹配 VoxCPM2
     */
    public boolean voxCpmProviderEnabled() {
        return VoxCpm2TtsProvider.PROVIDER_NAME.equalsIgnoreCase(provider);
    }

    /**
     * 当前 provider 的 HTTP 基础地址，供运行时健康检查和日志使用。
     *
     * @return provider base url
     */
    public String providerBaseUrl() {
        if (voxCpmProviderEnabled()) {
            return voxCpm2.getBaseUrl();
        }
        return gptSoVits.getBaseUrl();
    }

    /**
     * GPT-SoVITS HTTP provider 配置。
     * <p>
     * GPT-SoVITS 的 /tts 接口要求提供参考音频、参考文本和语言参数。这里保存的是默认角色音色，
     * 每段 RP 文本都会用这组默认参数发给 sidecar。
     */
    @Data
    public static class GptSoVitsConfig {
        private String baseUrl = "http://127.0.0.1:9880";
        private String refAudioPath = "";
        private List<String> auxRefAudioPaths = List.of();
        private String promptText = "";
        private String textLang = "zh";
        private String promptLang = "zh";
        private String mediaType = "wav";
        private String textSplitMethod = "cut0";
        private int topK = 15;
        private double topP = 1.0;
        private double temperature = 1.0;
        private int batchSize = 4;
        private double batchThreshold = 0.75;
        private boolean splitBucket = true;
        private double speedFactor = 1.0;
        private double fragmentInterval = 0.08;
        private int seed = -1;
        private int streamingMode = 0;
        private boolean parallelInfer = true;
        private double repetitionPenalty = 1.35;
        private int sampleSteps = 32;
        private boolean superSampling = false;
        private int overlapLength = 2;
        private int minChunkLength = 16;
    }

    /**
     * VoxCPM2 HTTP provider 配置。
     * <p>
     * 默认目标是基于 OpenBMB/VoxCPM 官方 Python API 的本地 sidecar。Java 只发送文本、参考音频、
     * 参考音频转写和推理参数；模型加载、权重路径和 GPU 管理由外部 VoxCPM2 运行时负责。
     */
    @Data
    public static class VoxCpm2Config {
        private String baseUrl = "http://127.0.0.1:8810";
        private String referenceWavPath = "";
        private String promptText = "";
        private double cfgValue = 2.0;
        private int inferenceTimesteps = 10;
        private boolean normalize = true;
        private boolean denoise = false;
        private String mediaType = "wav";
        private boolean streamingEnabled = true;
        private int badcaseRetryAttempts = 1;
        private Map<String, Object> extraBody = Map.of();
        private RuntimeConfig runtime = RuntimeConfig.voxCpm2Defaults();
    }

    /**
     * TTS sidecar 进程配置。
     * <p>
     * 这些字段只负责拉起外部服务，不描述模型权重本身。具体模型路径通常由外部项目自己的配置文件，
     * 或 startup-command 里的启动参数决定。
     */
    @Data
    public static class RuntimeConfig {
        private boolean autoStartEnabled = true;
        private List<String> startupCommand = List.of();
        private List<String> launcherCommand = List.of("uv", "run", "python");
        private String pythonExecutable = "";
        private String workingDirectory = "tts/runtime/GPT-SoVITS";
        private String apiServerScript = "api_v2.py";
        private String ttsConfigPath = "GPT_SoVITS/configs/tts_infer.yaml";
        private String bindAddress = "127.0.0.1";
        private int port = 9880;
        private String healthPath = "/docs";
        private String logFilePath = "gpt-sovits-server.log";
        private long startupWaitMs = 1000;
        private int maxRestartAttempts = 1;
        private List<String> extraArgs = List.of();
        private Map<String, String> environment = Map.of();

        /**
         * VoxCPM2 本地 sidecar 默认启动配置。
         * <p>
         * 默认工作目录是 {@code backend/tts/runtime/VoxCPM2}，命令使用该目录下的虚拟环境、
         * 本地模型目录和仓库内的 sidecar 脚本。
         *
         * @return VoxCPM2 runtime 默认值
         */
        public static RuntimeConfig voxCpm2Defaults() {
            RuntimeConfig runtime = new RuntimeConfig();
            runtime.setStartupCommand(List.of(
                    ".venv\\Scripts\\python.exe",
                    "..\\..\\..\\tools\\voxcpm2_tts_server.py",
                    "--model-id",
                    "models\\VoxCPM2",
                    "--device",
                    "cuda",
                    "--host",
                    "127.0.0.1",
                    "--port",
                    "8810"
            ));
            runtime.setLauncherCommand(List.of());
            runtime.setPythonExecutable("");
            runtime.setWorkingDirectory("tts/runtime/VoxCPM2");
            runtime.setApiServerScript("");
            runtime.setTtsConfigPath("");
            runtime.setBindAddress("127.0.0.1");
            runtime.setPort(8810);
            runtime.setHealthPath("/health");
            runtime.setLogFilePath("voxcpm2-server.log");
            runtime.setStartupWaitMs(1000);
            runtime.setMaxRestartAttempts(1);
            runtime.setExtraArgs(List.of());
            runtime.setEnvironment(Map.of());
            return runtime;
        }
    }
}
