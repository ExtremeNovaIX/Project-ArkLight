package p1.config.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * gamer 工作记忆配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "gamer.memory")
public class GamerMemoryProperties {

    /**
     * 未压缩的近期决策保留数量。
     */
    private int recentDecisionLimit = 20;

    /**
     * 每累计多少次 gamer 决策后，把近期决策压缩进阶段摘要。
     */
    private int stageCompressDecisionInterval = 16;

    /**
     * 每累计多少个阶段摘要后，把阶段摘要压缩进全局 run 摘要。
     */
    private int runCompressStageInterval = 10;

    /**
     * gamer 决策复盘 Markdown 是否启用。
     */
    private boolean traceEnabled = true;

    /**
     * gamer 决策复盘 Markdown 输出目录。
     */
    private String traceDirectory = "data/gamer-traces";
}
