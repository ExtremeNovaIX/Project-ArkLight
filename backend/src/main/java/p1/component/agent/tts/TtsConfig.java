package p1.component.agent.tts;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
@Data
@ConfigurationProperties(prefix = "tts")
public class TtsConfig {

    private boolean enabled = false;
    private String provider = "gpt-sovits-http";
    private long synthesisTimeoutMs = 60_000;
    private int firstChunkMinChars = 6;
    private int firstChunkChars = 12;
    private int maxChunkChars = 40;
    private List<Character> sentenceEndMarks = List.of('\u3002', '\uff01', '\uff1f', '\uff1b', '\u2026', '.', '!', '?', ';', '\n');
    private List<Character> firstChunkEndMarks = List.of('\u3002', '\uff01', '\uff1f', '\uff1b', '\u2026', '\uff0c', '\u3001', '.', '!', '?', ';', ',', ':', '\uff1a', '\n');
    private GptSoVitsConfig gptSoVits = new GptSoVitsConfig();
    private RuntimeConfig runtime = new RuntimeConfig();

    public boolean enabled() {
        return enabled;
    }

    public Duration synthesisTimeout() {
        return Duration.ofMillis(Math.max(1000, synthesisTimeoutMs));
    }

    public int maxChunkChars() {
        return Math.max(1, maxChunkChars);
    }

    public int firstChunkChars() {
        return Math.max(1, firstChunkChars);
    }

    public int firstChunkMinChars() {
        return Math.max(1, firstChunkMinChars);
    }

    public List<Character> firstChunkEndMarks() {
        return firstChunkEndMarks == null || firstChunkEndMarks.isEmpty()
                ? List.of('\u3002', '\uff01', '\uff1f', '\uff1b', '\u2026', '\uff0c', '\u3001', '.', '!', '?', ';', ',', ':', '\uff1a', '\n')
                : firstChunkEndMarks;
    }

    public List<Character> sentenceEndMarks() {
        return sentenceEndMarks == null || sentenceEndMarks.isEmpty()
                ? List.of('\u3002', '\uff01', '\uff1f', '\uff1b', '\u2026', '.', '!', '?', ';', '\n')
                : sentenceEndMarks;
    }

    public boolean gptSoVitsProviderEnabled() {
        return GptSoVitsTtsProvider.PROVIDER_NAME.equalsIgnoreCase(provider)
                || "gpt-sovits".equalsIgnoreCase(provider)
                || "gpt-so-vits".equalsIgnoreCase(provider);
    }

    public String providerBaseUrl() {
        return gptSoVits.getBaseUrl();
    }

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
    }
}