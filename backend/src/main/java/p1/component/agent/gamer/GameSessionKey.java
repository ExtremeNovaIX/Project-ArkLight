package p1.component.agent.gamer;

/**
 * 统一生成 gamer agent 的跨游戏会话键。
 */
public final class GameSessionKey {

    private static final String SEPARATOR = "-";

    private GameSessionKey() {
    }

    public static String of(String gameName, String sessionId) {
        return gameName + SEPARATOR + sessionId;
    }
}
