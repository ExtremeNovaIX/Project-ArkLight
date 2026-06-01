package p1.config.prop;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "assistant")
public class AssistantProperties {
    private Mode mode = Mode.API;
    private ModelServicesConfig modelServices = new ModelServicesConfig();
    private LlmLogsConfig llmLogs = new LlmLogsConfig();
    private ProviderConfig api;
    private ProviderConfig local;
    private EmbeddingStoreConfig embeddingStore;
    private MdRepositoryConfig mdRepository;
    private ChatMemoryConfig chatMemory;
    private RpConfig rp = new RpConfig();
    private InteractionConfig interaction = new InteractionConfig();
    private EventTreeConfig eventTree = new EventTreeConfig();

    public ChatModelConfig activeChatModel() {
        return activeRpModel();
    }

    public ChatModelConfig activeRpModel() {
        return activeServiceModel(modelServices.getRp());
    }

    public ChatModelConfig activeParserModel() {
        return activeServiceModel(modelServices.getParser());
    }

    public ChatModelConfig activeCheckerModel() {
        return activeServiceModel(modelServices.getChecker());
    }

    public ChatModelConfig activeSupervisorModel() {
        return activeServiceModel(modelServices.getSupervisor());
    }

    public EmbeddingModelConfig activeEmbeddingModel() {
        return activeProvider().getEmbeddingModel();
    }

    public ProviderConfig activeProvider() {
        return mode == Mode.LOCAL ? local : api;
    }

    public enum Mode {
        API,
        LOCAL
    }

    @Data
    public static class LlmLogsConfig {
        /**
         * 应用侧 LLM 调用跟踪的控制台开关。
         * <p>
         * 该配置只控制可读诊断日志是否输出到控制台；token 统计始终记录。
         * 底层模型客户端原生日志由 {@link ChatModelConfig#logEnabled} 控制。
         */
        private Map<String, Boolean> console = defaultConsoleConfig();

        public boolean consoleEnabled(String serviceName) {
            if (serviceName == null || serviceName.isBlank()) {
                return false;
            }
            return console.getOrDefault(serviceName.trim().toLowerCase(), false);
        }

        private static Map<String, Boolean> defaultConsoleConfig() {
            Map<String, Boolean> defaults = new LinkedHashMap<>();
            defaults.put("rp", true);
            defaults.put("parser", false);
            defaults.put("checker", false);
            defaults.put("supervisor", false);
            return defaults;
        }
    }

    @Data
    public static class ProviderConfig {
        private ChatModelConfig lightModel;
        private ChatModelConfig heavyModel;
        private EmbeddingModelConfig embeddingModel;
    }

    @Data
    public static class ModelServicesConfig {
        private String rp = "heavy";
        private String parser = "light";
        private String checker = "light";
        private String supervisor = "heavy";
    }

    private ChatModelConfig activeServiceModel(String target) {
        String normalized = normalizeModelTarget(target);
        if ("heavy".equals(normalized)) {
            return activeHeavyModel();
        }
        return activeLightModel();
    }

    private ChatModelConfig activeLightModel() {
        ProviderConfig provider = activeProvider();
        if (provider.getLightModel() != null) {
            return provider.getLightModel();
        }
        return provider.getHeavyModel();
    }

    private ChatModelConfig activeHeavyModel() {
        ProviderConfig provider = activeProvider();
        return mergedChatModel(provider.getLightModel(), provider.getHeavyModel());
    }

    private String normalizeModelTarget(String target) {
        if (target == null || target.isBlank()) {
            return "";
        }
        String normalized = target.trim().toLowerCase().replace('_', '-');
        if (normalized.endsWith("-model")) {
            normalized = normalized.substring(0, normalized.length() - "-model".length());
        }
        if ("lite".equals(normalized)) {
            return "light";
        }
        return normalized;
    }

    private ChatModelConfig mergedChatModel(ChatModelConfig base, ChatModelConfig override) {
        if (override == null) {
            return base;
        }
        ChatModelConfig merged = new ChatModelConfig();
        merged.setApiKey(firstNonBlank(override.getApiKey(), base == null ? null : base.getApiKey()));
        merged.setBaseUrl(firstNonBlank(override.getBaseUrl(), base == null ? null : base.getBaseUrl()));
        merged.setModelName(firstNonBlank(override.getModelName(), base == null ? null : base.getModelName()));
        merged.setTimeoutSeconds(override.getTimeoutSeconds() != null
                ? override.getTimeoutSeconds()
                : base == null ? null : base.getTimeoutSeconds());
        merged.setLogEnabled(override.isLogEnabled());
        merged.setPrompt(firstNonBlank(override.getPrompt(), base == null ? null : base.getPrompt()));
        merged.setReturnThinking(override.isReturnThinking());
        merged.setSendThinking(override.isSendThinking());
        merged.setReasoningEffort(firstNonBlank(override.getReasoningEffort(), base == null ? null : base.getReasoningEffort()));
        merged.setThinkingType(firstNonBlank(override.getThinkingType(), base == null ? null : base.getThinkingType()));
        return merged;
    }

    private String firstNonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    @Data
    public static class ChatModelConfig {
        private String apiKey;
        private String baseUrl;
        private String modelName;
        private Long timeoutSeconds;
        private boolean logEnabled;
        private String prompt;
        /**
         * 是否接收模型返回的 reasoning_content / thinking 字段。
         */
        private boolean returnThinking = true;
        /**
         * 是否把 thinking 再发送给模型。
         * <p>
         * 含工具调用的 thinking 模型通常要求把上一轮 reasoning_content 回传，
         * 默认开启以保证多轮工具循环协议完整；历史上下文膨胀交给记忆压缩链路处理。
         */
        private boolean sendThinking = true;
        /**
         * 支持 reasoning_effort 的模型可用 low/medium/high 等值；为空时不发送该参数。
         */
        private String reasoningEffort;
        /**
         * 兼容部分供应商的 thinking.type 参数，例如 disabled/enabled；为空时不发送。
         */
        private String thinkingType;
    }

    @Data
    public static class ChatMemoryConfig {
        private Integer compressCount;
        private Integer triggerCompressThreshold;
        private int contextMaxSummaryCount;
    }

    @Data
    public static class RpConfig {
        /**
         * 是否在 RP 收到每条用户消息时先执行通用指令路由。
         * <p>
         * 这里只控制 RP 是否消费路由结果；路由模型地址、模型名和超时等固定参数写在通用路由配置类中。
         */
        private boolean instructionRouterEnabled = false;
        /**
         * RP 主动发言配置。
         */
        private ProactiveConfig proactive = new ProactiveConfig();
    }

    @Data
    public static class ProactiveConfig {
        /**
         * 是否开启 RP 主动发言。
         */
        private boolean enabled = true;
        /**
         * 明显空闲多久后允许 RP 主动开口。
         */
        private long idleThresholdMs = 60000;
        /**
         * 主动发言之间的最小间隔，避免空闲状态下刷屏。
         */
        private long speechCooldownMs = 30000;
        /**
         * 游戏模式空闲多久后允许 RP 主动开口。
         */
        private long gameIdleThresholdMs = 120000;
        /**
         * 游戏模式空闲主动发言之间的最小间隔。
         */
        private long gameSpeechCooldownMs = 120000;
        /**
         * 游戏模式主动发言频率预算的滑动窗口时长。
         */
        private long gameProactiveRateWindowMs = 10000;
        /**
         * 游戏模式主动发言频率窗口内最多允许的发言次数。
         */
        private int gameProactiveMaxSpeechesPerWindow = 2;
        /**
         * 空闲扫描周期。
         */
        private long idleScanIntervalMs = 10000;
        /**
         * 空闲触发命中后等待多久再真正生成主动发言。
         * <p>
         * 该窗口用于吸收“用户刚好在空闲触发附近发消息”的竞态，避免主动消息和正常回复叠在一起。
         */
        private long idleSpeechGraceMs = 5000;
        /**
         * 非游戏状态下用户持续不回复时，主动发言退避到的最长间隔。
         */
        private long nonGameMaxIdleIntervalMs = 43200000;
    }

    @Data
    public static class InteractionConfig {
        /**
         * 一次用户文本请求持有交互窗口的兜底超时，单位毫秒。
         */
        private long userTurnTtlMs = 180000;
        /**
         * 前端 typing 或未来语音 VAD 心跳暂停游戏自动行动的用户活动窗口，输入停止后按该时长恢复，单位毫秒。
         */
        private long userActivityTtlMs = 3000;
        /**
         * RP 从首个可见响应字符到流结束期间持有交互窗口的兜底超时，单位毫秒。
         */
        private long rpSpeechTtlMs = 180000;
        /**
         * 游戏等待调度配置。
         */
        private WaitConfig wait = new WaitConfig();
    }

    @Data
    public static class WaitConfig {
        /**
         * RP 判断用户要求“等一下”时，默认等待多久后询问用户是否继续，单位秒。
         */
        private long defaultSeconds = 10;
        /**
         * 等待 hold 的最长保留时间，防止用户离开后会话永久阻塞，单位分钟。
         */
        private long maxHoldMinutes = 30;
        /**
         * 等待计时到点后，直接投递给前端的 RP 询问文本。
         */
        private String reminderText = "好了没？";
    }

    @Data
    public static class EmbeddingModelConfig {
        private String apiKey;
        private String baseUrl;
        private String modelName;
    }

    @Data
    public static class EmbeddingStoreConfig {
        private String path;
    }

    @Data
    public static class MdRepositoryConfig {
        private String path = "data/memory";
    }

    @Data
    public static class EventTreeConfig {
        // recent-24h 候选的基础向量分至少达到这个阈值，才允许被拿来连边。
        private double recentWindowScoreLinkThreshold = 0.7;
        // recent 事件组窗口的保留时长，超出这个小时数的组会被 recent-window 维护逻辑淘汰。
        private int recentWindowHours = 24;
        // recent-window 过期扫描任务的固定执行间隔。
        private long recentWindowScanFixedDelayMs = 3600000;
        // 共享 tag 的 IDF boost 权重。最终候选分 = 向量基础分 * (1 + weight * idf01)。
        // 调大后，稀有共享 tag 对 recent-window rerank 的影响会更强。
        private double recentWindowIdfBoostWeight = 0.35;
        // IDF 公式里 N 侧的平滑项，用来避免 recent 组数量较少时波动过大。
        // 原始公式中的分子为 N + docCountSmoothing。
        private double recentWindowIdfDocCountSmoothing = 1.0;
        // IDF 公式里 df(tag) 侧的平滑项，用来避免 tag 只出现极少次数时权重异常放大。
        // 原始公式中的分母为 df(tag) + dfSmoothing。
        private double recentWindowIdfDfSmoothing = 1.0;
        // 将原始 IDF 累积分数压到 [0,1] 的归一化尺度。
        // 调小会更快饱和，调大会让 boost 增长更平缓。
        private double recentWindowIdfNormalizationScale = 2.0;
        // 至少共享多少个 tag 才触发 IDF boost；低于这个数量时完全不加成。
        private int recentWindowIdfMinSharedTags = 1;
        // recent-window 候选的时间衰减系数。最终时间因子 = exp(-coefficient * ageHours / recentWindowHours)。
        // 0 表示关闭时间衰减；调大后，越接近窗口尾部的旧组越难在 recent-window 竞争中胜出。
        private double recentWindowTimeDecayCoefficient = 0.5;
        // 最终连接边的最低得分阈值，低于这个值的边会被拦截。
        private double recentWindowFinalThreshold = 0.7;
    }
}
