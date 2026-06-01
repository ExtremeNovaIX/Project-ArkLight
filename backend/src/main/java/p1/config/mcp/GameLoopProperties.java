package p1.config.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 游戏循环配置。
 * <p>
 * 快速轮询负责探测行动窗口；每个 tick 最多触发一次 RP 决策，动作后的同状态等待由循环层处理。
 */
@Data
@Component
@ConfigurationProperties(prefix = "game.loop")
public class GameLoopProperties {
    /**
     * 轮询间隔（毫秒），检查是否轮到 AI 行动。
     */
    private long pollIntervalMs = 500;
    /**
     * 无有效动作时的慢观察退避配置。
     */
    private SlowObserveConfig slowObserve = new SlowObserveConfig();

    @Data
    public static class SlowObserveConfig {
        /**
         * 是否启用慢观察退避。
         */
        private boolean enabled = true;
        /**
         * 连续多少次没有有效动作后开始退避。
         */
        private int threshold = 2;
        /**
         * 首次退避观察间隔，单位毫秒。
         */
        private long initialMs = 1000;
        /**
         * 每次继续无动作时增加的观察间隔，单位毫秒。
         */
        private long stepMs = 1000;
        /**
         * 慢观察最大间隔，单位毫秒。
         */
        private long maxMs = 3000;
    }
}
