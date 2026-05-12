package p1.component.agent.streaming;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用流式 JSON 指令解析器。
 * <p>
 * 该解析器不理解任何游戏业务，只负责从持续到达的模型文本里截取完整 JSON 对象。
 * 它会正确处理字符串内的大括号和转义字符；业务层需要继续校验 JSON schema。
 */
public class StreamingJsonInstructionParser {

    private final ObjectMapper objectMapper;
    private final int maxCandidateChars;

    private StringBuilder candidate = new StringBuilder();
    private boolean capturing = false;
    private boolean inString = false;
    private boolean escaped = false;
    private int depth = 0;

    /**
     * 创建流式 JSON 指令解析器。
     *
     * @param objectMapper      JSON 解析器
     * @param maxCandidateChars 单个 JSON 候选最大字符数
     */
    public StreamingJsonInstructionParser(ObjectMapper objectMapper, int maxCandidateChars) {
        this.objectMapper = objectMapper;
        this.maxCandidateChars = Math.max(128, maxCandidateChars);
    }

    /**
     * 接收一段流式文本，并返回本段文本内闭合完成的 JSON 对象。
     *
     * @param chunk 模型新输出的文本片段
     * @return 已成功解析的 JSON 指令列表
     */
    public List<StreamingJsonInstruction> accept(String chunk) {
        List<StreamingJsonInstruction> instructions = new ArrayList<>();
        if (chunk == null || chunk.isEmpty()) {
            return instructions;
        }

        for (int i = 0; i < chunk.length(); i++) {
            char ch = chunk.charAt(i);
            if (!capturing) {
                if (ch == '{') {
                    startCandidate(ch);
                }
                continue;
            }

            appendCandidate(ch);
            if (!capturing) {
                continue;
            }

            updateState(ch);
            if (!inString && depth == 0) {
                parseCurrentCandidate(instructions);
                resetCandidate();
            }
        }
        return instructions;
    }

    /**
     * 开始捕获一个 JSON 候选对象。
     */
    private void startCandidate(char ch) {
        candidate = new StringBuilder();
        candidate.append(ch);
        capturing = true;
        inString = false;
        escaped = false;
        depth = 1;
    }

    /**
     * 追加候选字符，并在候选过长时丢弃。
     */
    private void appendCandidate(char ch) {
        candidate.append(ch);
        if (candidate.length() > maxCandidateChars) {
            resetCandidate();
        }
    }

    /**
     * 更新字符串、转义和大括号深度状态。
     */
    private void updateState(char ch) {
        if (escaped) {
            escaped = false;
            return;
        }
        if (inString && ch == '\\') {
            escaped = true;
            return;
        }
        if (ch == '"') {
            inString = !inString;
            return;
        }
        if (inString) {
            return;
        }
        if (ch == '{') {
            depth++;
        } else if (ch == '}') {
            depth--;
        }
    }

    /**
     * 尝试解析当前候选对象，失败时静默丢弃该候选。
     */
    private void parseCurrentCandidate(List<StreamingJsonInstruction> instructions) {
        String raw = candidate.toString();
        try {
            JsonNode json = objectMapper.readTree(raw);
            if (json != null && json.isObject()) {
                instructions.add(new StreamingJsonInstruction(raw, json));
            }
        } catch (Exception ignored) {
            // 模型可能输出自然语言中的伪 JSON；解析失败时交给后续文本继续寻找下一个候选。
        }
    }

    /**
     * 重置当前候选状态。
     */
    private void resetCandidate() {
        candidate = new StringBuilder();
        capturing = false;
        inString = false;
        escaped = false;
        depth = 0;
    }
}
