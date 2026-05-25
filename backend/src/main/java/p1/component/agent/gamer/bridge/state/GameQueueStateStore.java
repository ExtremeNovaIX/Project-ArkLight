package p1.component.agent.gamer.bridge.state;

import org.springframework.stereotype.Component;
import p1.component.agent.gamer.adapter.core.GameStateSnapshot;
import p1.component.agent.gamer.bridge.GameBridgeActionStatus;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 游戏队列处理器的会话态存储。
 * <p>
 * 该组件只保存桥接层运行时状态，不负责执行 MCP 指令，也不负责渲染提示词。
 * 目前是纯内存实现，应用重启后丢弃。
 */
@Component
public class GameQueueStateStore {

    private final Map<String, GameStateSnapshot> planningStates = new ConcurrentHashMap<>();
    private final Map<String, String> notices = new ConcurrentHashMap<>();
    private final Map<String, GameBridgeActionStatus> lastStatuses = new ConcurrentHashMap<>();
    private final Map<String, String> lastActionResults = new ConcurrentHashMap<>();

    /**
     * 记录 agent 做计划时看到的状态。
     *
     * @param memoryId gamer 会话 key
     * @param state    最新游戏状态
     */
    public void rememberPlanningState(String memoryId, GameStateSnapshot state) {
        planningStates.put(memoryId, state);
    }

    /**
     * 查询 agent 做计划时看到的状态。
     *
     * @param memoryId gamer 会话 key
     * @return 已记录状态；没有时返回 null
     */
    public GameStateSnapshot planningState(String memoryId) {
        return planningStates.get(memoryId);
    }

    /**
     * 写入下一轮需要提醒 agent 的桥接层提示。
     *
     * @param memoryId gamer 会话 key
     * @param notice   提示文本
     */
    public void putNotice(String memoryId, String notice) {
        if (notice == null || notice.isBlank()) {
            notices.remove(memoryId);
            return;
        }
        notices.put(memoryId, notice);
    }

    /**
     * 取出并删除桥接层提示。
     *
     * @param memoryId gamer 会话 key
     * @return 提示文本；没有时返回 null
     */
    public String consumeNotice(String memoryId) {
        return notices.remove(memoryId);
    }

    /**
     * 清空本轮动作状态。
     *
     * @param memoryId gamer 会话 key
     */
    public void clearLastStatus(String memoryId) {
        lastStatuses.remove(memoryId);
    }

    /**
     * 写入最近一次队列工具提交的结构化状态。
     *
     * @param memoryId gamer 会话 key
     * @param status   队列状态
     */
    public void putLastStatus(String memoryId, GameBridgeActionStatus status) {
        lastStatuses.put(memoryId, status == null ? GameBridgeActionStatus.UNKNOWN : status);
    }

    /**
     * 查询最近一次队列工具提交的结构化状态。
     *
     * @param memoryId gamer 会话 key
     * @return 最近状态；没有时返回 UNKNOWN
     */
    public GameBridgeActionStatus lastStatus(String memoryId) {
        return lastStatuses.getOrDefault(memoryId, GameBridgeActionStatus.UNKNOWN);
    }

    /**
     * 写入上一批操作结果。
     *
     * @param memoryId gamer 会话 key
     * @param result   面向 agent 的操作结果文本
     */
    public void rememberLastActionResult(String memoryId, String result) {
        if (result == null || result.isBlank()) {
            lastActionResults.remove(memoryId);
            return;
        }
        lastActionResults.put(memoryId, result);
    }

    /**
     * 查询上一批操作结果。
     *
     * @param memoryId gamer 会话 key
     * @return 上一批操作结果；没有时返回 null
     */
    public String peekLastActionResult(String memoryId) {
        return lastActionResults.get(memoryId);
    }
}
