package p1.component.agent.gamer.projection;

import org.springframework.stereotype.Service;
import p1.component.agent.gamer.adapter.core.GameOperation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * gamer 近期行动的运行时投影服务。
 * <p>
 * 该服务只保存 RP 动态上下文需要的 summary 和 operation.note，不承担 gamer 工作记忆压缩，
 * 也不向 RP 暴露工具名、参数、执行结果或技术中断原因。
 */
@Service
public class GamerActionSnapshotService {

    private static final int SNAPSHOT_LIMIT = 8;

    private final Map<String, SessionSnapshots> snapshotsBySession = new ConcurrentHashMap<>();

    /**
     * 记录一批已被桥接层接收的 gamer 行动摘要。
     *
     * @param gameName   游戏名
     * @param memoryId   gamer 会话 key
     * @param summary    gamer 决策摘要
     * @param operations gamer 提交的操作列表
     */
    public void record(String gameName,
                       String memoryId,
                       String summary,
                       List<GameOperation> operations) {
        String normalizedSummary = normalize(summary);
        List<String> notes = renderNotes(operations);
        if (normalizedSummary.isBlank() && notes.isEmpty()) {
            return;
        }

        SessionSnapshots snapshots = snapshotsBySession.computeIfAbsent(
                sessionKey(gameName, memoryId),
                ignored -> new SessionSnapshots());
        synchronized (snapshots) {
            snapshots.items.addLast(new ActionSnapshot(normalizedSummary, notes));
            while (snapshots.items.size() > SNAPSHOT_LIMIT) {
                snapshots.items.removeFirst();
            }
        }
    }

    /**
     * 渲染 RP 运行时上下文使用的近期行动快照。
     *
     * @param gameName 游戏名
     * @param memoryId gamer 会话 key
     * @return RP 可见的近期行动投影；没有内容时为空字符串
     */
    public String renderForRp(String gameName, String memoryId) {
        SessionSnapshots snapshots = snapshotsBySession.get(sessionKey(gameName, memoryId));
        if (snapshots == null) {
            return "";
        }

        synchronized (snapshots) {
            if (snapshots.items.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("<recent_game_actions>\n")
                    .append("这是我最近已做过或刚刚提交过的游戏行动摘要，不是新的战术任务。\n");
            int index = 1;
            for (ActionSnapshot snapshot : snapshots.items) {
                sb.append(index++).append(". 判断：").append(blankText(snapshot.summary())).append("\n");
                if (!snapshot.notes().isEmpty()) {
                    sb.append("   行动：").append(String.join("；", snapshot.notes())).append("\n");
                }
            }
            sb.append("</recent_game_actions>");
            return sb.toString().trim();
        }
    }

    /**
     * 从操作列表提取自然语言 note。
     *
     * @param operations gamer 提交的操作列表
     * @return 非空 note 列表
     */
    private List<String> renderNotes(List<GameOperation> operations) {
        if (operations == null || operations.isEmpty()) {
            return List.of();
        }

        List<String> notes = new ArrayList<>();
        for (GameOperation operation : operations) {
            String note = operation == null ? "" : normalize(operation.note());
            if (!note.isBlank()) {
                notes.add(note);
            }
        }
        return List.copyOf(notes);
    }

    /**
     * 构建投影存储使用的会话 key。
     *
     * @param gameName 游戏名
     * @param memoryId gamer 会话 key
     * @return 稳定 key
     */
    private String sessionKey(String gameName, String memoryId) {
        String safeGameName = normalize(gameName).isBlank() ? "unknown" : normalize(gameName);
        String safeMemoryId = normalize(memoryId).isBlank() ? "default" : normalize(memoryId);
        return safeGameName + "::" + safeMemoryId;
    }

    /**
     * 标准化可选文本。
     *
     * @param value 原始文本
     * @return 去除首尾空白后的文本
     */
    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 给 RP 投影的空摘要兜底。
     *
     * @param value 原始文本
     * @return 非空文本
     */
    private String blankText(String value) {
        return value == null || value.isBlank() ? "我根据当前局势做了一次游戏行动判断。" : value;
    }

    /**
     * 单会话行动快照窗口。
     */
    private static final class SessionSnapshots {
        private final Deque<ActionSnapshot> items = new ArrayDeque<>();
    }

    /**
     * 单批行动的 RP 投影。
     *
     * @param summary gamer 决策摘要
     * @param notes   操作意图 note
     */
    private record ActionSnapshot(String summary, List<String> notes) {
    }
}
