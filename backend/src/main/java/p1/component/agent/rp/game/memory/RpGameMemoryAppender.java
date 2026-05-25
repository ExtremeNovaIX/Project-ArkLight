package p1.component.agent.rp.game.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import p1.component.agent.gamer.adapter.core.GameOperation;
import p1.component.agent.gamer.bridge.GameBridgeActionStatus;
import p1.component.agent.gamer.loop.ActiveGameRegistry;
import p1.component.agent.gamer.loop.ActiveGameSession;

import java.util.List;

/**
 * 将游戏行动追加进 RP 现有记忆链路。
 * <p>
 * 该组件不建立新的游戏记忆系统，而是把 gamer 的行动摘要写成 RP 的 AI 消息，
 * 交给现有 ArchivableChatMemory、RawMd 和压缩流程处理。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RpGameMemoryAppender {

    private final ChatMemoryProvider chatMemoryProvider;
    private final ActiveGameRegistry activeGameRegistry;

    /**
     * 记录一批游戏行动到 RP 会话记忆。
     *
     * @param gameName        游戏名
     * @param gamerMemoryId   gamer 会话 key，格式为 gameName-sessionId
     * @param status          队列状态
     * @param summary         gamer 输出的本批决策摘要
     * @param operations      gamer 提交的操作列表，只使用 note，不写 tool/args
     * @param result          桥接层执行结果摘要；RP 记忆不会直接暴露技术执行结果
     * @param interruptReason 中断原因；RP 记忆不会直接暴露该技术原因
     */
    public void appendGameAction(String gameName,
                                 String gamerMemoryId,
                                 GameBridgeActionStatus status,
                                 String summary,
                                 List<GameOperation> operations,
                                 String result,
                                 String interruptReason) {
        String rpSessionId = rpSessionId(gameName, gamerMemoryId);
        String memoryText = renderMemoryText(gameName, summary, operations);
        if (memoryText.isBlank()) {
            return;
        }

        try {
            ChatMemory memory = chatMemoryProvider.get(rpSessionId);
            memory.add(AiMessage.from(memoryText));
            log.debug("[RP游戏记忆] 已追加游戏行动: game={}, rpSession={}, status={}", gameName, rpSessionId, status);
        } catch (Exception e) {
            log.warn("[RP游戏记忆] 追加游戏行动失败: game={}, rpSession={}, reason={}",
                    gameName, rpSessionId, e.getMessage());
        }
    }

    /**
     * 渲染写入 RP 记忆的自然语言行动摘要。
     *
     * @param gameName        游戏名
     * @param summary         gamer 决策摘要
     * @param operations      操作列表
     * @return 可直接进入 RP 记忆的 AI 消息
     */
    private String renderMemoryText(String gameName,
                                    String summary,
                                    List<GameOperation> operations) {
        StringBuilder sb = new StringBuilder();
        sb.append("【我刚刚完成的游戏行动】\n");
        sb.append("这是一段已经发生的经历，不是用户操作，也不是待决策计划。\n");
        sb.append("游戏：").append(gameName).append("\n");
        sb.append("我的判断：").append(blankToDefault(summary, "我根据当前局势做了一次游戏行动判断。")).append("\n");

        String operationNotes = renderOperationNotes(operations);
        if (!operationNotes.isBlank()) {
            sb.append("我的行动：\n").append(operationNotes).append("\n");
        }

        return sb.toString().trim();
    }

    /**
     * 渲染 operation.note 列表。
     *
     * @param operations 操作列表
     * @return 仅包含自然语言 note 的列表文本
     */
    private String renderOperationNotes(List<GameOperation> operations) {
        if (operations == null || operations.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (GameOperation operation : operations) {
            String note = operation == null ? "" : blankToDefault(operation.note(), "");
            if (note.isBlank()) {
                continue;
            }
            sb.append(index++).append(". ").append(cleanBridgeText(note)).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 从 gamer 会话 key 还原 RP 会话 id。
     *
     * @param gameName      游戏名
     * @param gamerMemoryId gamer 会话 key
     * @return RP 会话 id
     */
    private String rpSessionId(String gameName, String gamerMemoryId) {
        String gameSessionId = gameSessionId(gameName, gamerMemoryId);
        ActiveGameSession activeSession = activeGameRegistry.get(gameName, gameSessionId);
        if (activeSession != null) {
            return activeSession.getRpSessionId();
        }
        return gameSessionId;
    }

    /**
     * 从 gamer 会话 key 还原游戏侧 session id。
     *
     * @param gameName      游戏名
     * @param gamerMemoryId gamer 会话 key
     * @return 游戏侧 session id
     */
    private String gameSessionId(String gameName, String gamerMemoryId) {
        String prefix = gameName + "-";
        if (gamerMemoryId != null && gamerMemoryId.startsWith(prefix)) {
            return gamerMemoryId.substring(prefix.length());
        }
        return gamerMemoryId == null || gamerMemoryId.isBlank() ? "default" : gamerMemoryId;
    }

    /**
     * 清理容易污染 RP 口吻的桥接层词汇。
     *
     * @param value 原始文本
     * @return 自然语言文本
     */
    private String cleanBridgeText(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("enqueue_operations", "游戏行动")
                .replace("MCP", "游戏接口")
                .replace("bridge", "系统")
                .replace("桥接层", "系统")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * 空文本兜底。
     *
     * @param value        原始文本
     * @param defaultValue 默认文本
     * @return 非空文本
     */
    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
