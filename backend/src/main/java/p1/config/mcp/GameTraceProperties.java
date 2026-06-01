package p1.config.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 游戏操作复盘配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "game.trace")
public class GameTraceProperties {

    /**
     * 游戏操作复盘 Markdown 是否启用。
     */
    private boolean traceEnabled = true;

    /**
     * 游戏操作复盘 Markdown 输出目录。
     */
    private String traceDirectory = "data/game-traces";
}
