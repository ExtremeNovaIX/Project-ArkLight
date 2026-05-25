package p1.component.agent.stt;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * STT (Speech-to-Text) 工程级默认配置。
 * <p>
 * 硬编码 sherpa-onnx WebSocket 服务器的模型路径、端口和运行时参数。
 * 需要换模型时修改这里，或替换这个 bean。
 */
@Component
public class SttConfig {

    /** sherpa-onnx WebSocket 服务器监听端口 */
    public int serverPort() {
        return 6006;
    }

    /** sherpa-onnx 可执行文件路径（相对于项目根目录） */
    public String executablePath() {
        return "llm/sherpa-onnx/bin/sherpa-onnx-online-websocket-server.exe";
    }

    /** tokens.txt 路径 */
    public String tokensPath() {
        return "llm/sherpa-onnx/models/zipformer-zh/tokens.txt";
    }

    /** encoder.onnx 路径 */
    public String encoderPath() {
        return "llm/sherpa-onnx/models/zipformer-zh/encoder-epoch-99-avg-1.onnx";
    }

    /** decoder.onnx 路径 */
    public String decoderPath() {
        return "llm/sherpa-onnx/models/zipformer-zh/decoder-epoch-99-avg-1.onnx";
    }

    /** joiner.onnx 路径 */
    public String joinerPath() {
        return "llm/sherpa-onnx/models/zipformer-zh/joiner-epoch-99-avg-1.onnx";
    }

    /** 模型类型，加速加载 */
    public String modelType() {
        return "zipformer";
    }

    /** 推理后端：cpu 或 cuda */
    public String provider() {
        return "cpu";
    }

    /** 工作线程数 */
    public int numWorkThreads() {
        return 3;
    }

    /** 日志文件路径 */
    public String logFilePath() {
        return "llm/sherpa-onnx/bin/asr-server.log";
    }

    /** 是否由 Spring 启动时拉起 STT 服务器 */
    public boolean runtimeAutoStartEnabled() {
        return true;
    }

    /** 启动命令 */
    public List<String> runtimeCommand() {
        return List.of(
                executablePath(),
                "--port=" + serverPort(),
                "--tokens=" + tokensPath(),
                "--encoder=" + encoderPath(),
                "--decoder=" + decoderPath(),
                "--joiner=" + joinerPath(),
                "--model-type=" + modelType(),
                "--provider=" + provider(),
                "--num-work-threads=" + numWorkThreads(),
                "--log-file=" + logFilePath()
        );
    }

    /** 进程工作目录 */
    public String runtimeWorkingDirectory() {
        return "";
    }

    /** 进程启动后等待时间（毫秒） */
    public long runtimeStartupWaitMs() {
        return 500;
    }
}
