package p1.component.agent.router;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 通用指令路由模型配置。
 * <p>
 * 这里刻意不走 yaml：指令路由是一个低延迟基础设施组件，模型地址、模型名、超时和输出长度
 * 作为工程级默认值集中写死在这个类里。需要换模型时改这里，或在测试/未来扩展中替换这个 bean。
 */
@Component
public class InstructionRouterModelConfig {

    /**
     * OpenAI-compatible chat completion 地址。
     *
     * @return 本地路由 sidecar 地址
     */
    public String endpointUrl() {
        return "http://127.0.0.1:8087/v1/chat/completions";
    }

    /**
     * 传给本地 sidecar 的模型名。
     *
     * @return 模型名称
     */
    public String modelName() {
        return "local-instruction-router";
    }

    /**
     * 本地 sidecar 通常不需要 API key。
     *
     * @return API key；为空表示不发送 Authorization
     */
    public String apiKey() {
        return "";
    }

    /**
     * 单次路由 HTTP 超时。
     *
     * @return 超时时间，单位毫秒
     */
    public long timeoutMs() {
        return 500;
    }

    /**
     * 路由模型最大输出 token。
     *
     * @return 最大输出 token
     */
    public int maxTokens() {
        return 128;
    }

    /**
     * 闭集分类温度。
     *
     * @return temperature
     */
    public double temperature() {
        return 0.0;
    }

    /**
     * RP 游戏控制采纳路由结果的默认置信度阈值。
     *
     * @return 最低置信度
     */
    public double minConfidence() {
        return 0.65;
    }

    /**
     * 是否由 Spring 启动时拉起本地模型进程。
     *
     * @return true 表示启动 sidecar
     */
    public boolean runtimeAutoStartEnabled() {
        return true;
    }

    /**
     * 本地模型启动命令。
     *
     * @return 按参数拆分的启动命令；为空表示不启动
     */
    public List<String> runtimeCommand() {
        return List.of("llm/llama-server.exe", "-m", "llm/models/Qwen3.5-0.8B-Q8_0.gguf", "--port", "8087", "-ngl", "99", "-c", "4096", "-np", "2", "-ctk", "q4_0", "-ctv", "q4_0", "--cache-ram", "512");
    }

    /**
     * 本地模型进程工作目录。
     *
     * @return 工作目录；为空表示使用当前应用工作目录
     */
    public String runtimeWorkingDirectory() {
        return "";
    }

    /**
     * 进程启动后等待多久再接受请求。
     *
     * @return 等待时间，单位毫秒
     */
    public long runtimeStartupWaitMs() {
        return 1000;
    }
}
