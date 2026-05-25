package p1.config.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Gamer agent 通用配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "gamer")
public class GamerProperties {
    /**
     * 请求未提供 gameName 时使用的默认游戏。
     * 为空时，如果当前只有一个已启用 MCP 游戏，会自动选择该游戏。
     */
    private String defaultGameName;
    /**
     * 请求未提供 sessionId 时使用的默认会话。
     */
    private String defaultSessionId = "default";
    /**
     * MCP 客户端上报给 MCP server 的客户端名称。
     */
    private String clientName = "ArcLight-GameAgent";
    /**
     * MCP 客户端版本。
     */
    private String clientVersion = "1.0";
    /**
     * 流式微指令执行配置。
     */
    private Streaming streaming = new Streaming();

    /**
     * gamer 流式执行配置。
     */
    @Data
    public static class Streaming {
        /**
         * 单次模型 stream 内最多执行多少个 ACTION，防止模型失控连续输出。
         */
        private int maxActionsPerStream = 12;
        /**
         * 单次 stream 最长等待时间，超时后取消流并等待下一轮 loop。
         */
        private long maxStreamWaitMs = 30000;
        /**
         * 首个 ACTION 之前允许的最长等待时间。
         */
        private long firstActionTimeoutMs = 12000;
        /**
         * JSON 候选片段最大字符数，超过后解析器会丢弃当前候选。
         */
        private int maxJsonCandidateChars = 12000;
        /**
         * 每次写入队列复盘的 reasoning_content 最大字符数。
         */
        private int reasoningTraceMaxChars = 4000;
        /**
         * 是否从模型 thinking/reasoning_content 流中解析 ACTION JSON。
         * gamer 默认禁用 thinking，因此该开关默认关闭。
         */
        private boolean parseThinkingChunks = false;
        /**
         * 是否从普通 response 流中解析 ACTION JSON。
         * gamer 的 ACTION 协议默认从可见响应通道解析。
         */
        private boolean parseResponseChunks = true;
        /**
         * 流式调用结束后，是否用 SLF4J 打一整块汇总日志。
         */
        private boolean logStreamSummary = true;
        /**
         * 汇总日志中 thinking/response 各自最多保留多少字符。
         */
        private int logSummaryMaxChars = 6000;
    }
}
