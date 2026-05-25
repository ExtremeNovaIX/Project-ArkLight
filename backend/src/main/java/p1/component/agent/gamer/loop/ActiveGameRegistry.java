package p1.component.agent.gamer.loop;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活跃游戏会话注册表。
 * <p>
 * 该组件只保存当前进程内的循环会话状态，不负责持久化。
 */
@Component
@Slf4j
public class ActiveGameRegistry {

    /**
     * key = gameName:sessionId。
     */
    private final Map<String, ActiveGameSession> sessions = new ConcurrentHashMap<>();

    /**
     * 构建注册表内部使用的会话 key。
     *
     * @param gameName  游戏名
     * @param sessionId 用户侧会话 id
     * @return 唯一会话 key
     */
    private static String key(String gameName, String sessionId) {
        return gameName + ":" + sessionId;
    }

    /**
     * 注册一个新的运行中会话。
     *
     * @param gameName  游戏名
     * @param sessionId 用户侧会话 id
     * @return 新注册的活跃会话
     */
    public ActiveGameSession register(String gameName, String sessionId) {
        return register(gameName, sessionId, sessionId);
    }

    /**
     * 注册一个新的运行中会话，并显式绑定 RP 会话。
     *
     * @param gameName    游戏名
     * @param sessionId   游戏侧会话 id
     * @param rpSessionId RP 会话 id
     * @return 新注册的活跃会话
     */
    public ActiveGameSession register(String gameName, String sessionId, String rpSessionId) {
        ActiveGameSession session = new ActiveGameSession(gameName, sessionId, rpSessionId);
        ActiveGameSession existing = sessions.putIfAbsent(key(gameName, sessionId), session);
        if (existing != null) {
            // start 语义是重新开始循环，因此同名会话存在时用新会话覆盖旧会话。
            log.warn("[游戏循环] 会话已存在，覆盖: game={}, session={}", gameName, sessionId);
            sessions.put(key(gameName, sessionId), session);
        }
        log.info("[游戏循环] 会话已注册: game={}, session={}, rpSession={}", gameName, sessionId, session.getRpSessionId());
        return session;
    }

    /**
     * 注销一个会话。
     *
     * @param gameName  游戏名
     * @param sessionId 用户侧会话 id
     */
    public void unregister(String gameName, String sessionId) {
        ActiveGameSession removed = sessions.remove(key(gameName, sessionId));
        if (removed != null) {
            removed.setState(ActiveGameSession.State.STOPPED);
            log.info("[游戏循环] 会话已注销: game={}, session={}", gameName, sessionId);
        }
    }

    /**
     * 查询一个会话。
     *
     * @param gameName  游戏名
     * @param sessionId 用户侧会话 id
     * @return 会话对象；不存在时返回 null
     */
    public ActiveGameSession get(String gameName, String sessionId) {
        return sessions.get(key(gameName, sessionId));
    }

    /**
     * 按用户侧 RP 会话 id 查找当前活跃的游戏会话。
     * <p>
     * RP agent 只知道自己的会话 id，不应该关心具体游戏循环的内部 key；
     * 这里把“是否处于游戏模式”的判断集中在注册表里，供动态工具层和动态状态层复用。
     *
     * @param sessionId RP 会话 id
     * @return 优先返回 RUNNING 会话；没有运行中会话时返回 PAUSED 会话；STOPPED 会话视为不存在
     */
    public Optional<ActiveGameSession> findBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return sessions.values().stream()
                .filter(session -> sessionId.equals(session.getRpSessionId()))
                .filter(session -> session.getState() != ActiveGameSession.State.STOPPED)
                .min(Comparator
                        .comparingInt((ActiveGameSession session) -> statePriority(session.getState()))
                        .thenComparing(ActiveGameSession::getGameName));
    }

    /**
     * 为同一 RP 会话下的多个游戏会话确定选择顺序。
     *
     * @param state 游戏循环状态
     * @return 数字越小优先级越高
     */
    private int statePriority(ActiveGameSession.State state) {
        if (state == ActiveGameSession.State.RUNNING) {
            return 0;
        }
        if (state == ActiveGameSession.State.PAUSED) {
            return 1;
        }
        return 2;
    }

    /**
     * 列出所有运行中的会话。
     *
     * @return 当前状态为 RUNNING 的会话集合
     */
    public Collection<ActiveGameSession> listRunning() {
        return sessions.values().stream()
                .filter(session -> session.getState() == ActiveGameSession.State.RUNNING)
                .toList();
    }

    /**
     * 列出所有会话。
     *
     * @return 当前注册表中的全部会话集合
     */
    public Collection<ActiveGameSession> listAll() {
        return sessions.values();
    }
}
