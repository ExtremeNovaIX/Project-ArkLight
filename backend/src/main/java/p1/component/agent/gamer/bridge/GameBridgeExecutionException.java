package p1.component.agent.gamer.bridge;

/**
 * 桥接层无法完成 RP 动作时抛出。
 */
public class GameBridgeExecutionException extends RuntimeException {

    public enum Kind {
        FAILURE,
        INTERACTION_DEFERRED
    }

    private final String feedback;
    private final Kind kind;

    public GameBridgeExecutionException(String feedback) {
        this(feedback, Kind.FAILURE, null);
    }

    public GameBridgeExecutionException(String feedback, Throwable cause) {
        this(feedback, Kind.FAILURE, cause);
    }

    public GameBridgeExecutionException(String feedback, Kind kind) {
        this(feedback, kind, null);
    }

    public GameBridgeExecutionException(String feedback, Kind kind, Throwable cause) {
        super(feedback, cause);
        this.feedback = normalize(feedback);
        this.kind = kind == null ? Kind.FAILURE : kind;
    }

    public String feedback() {
        return feedback;
    }

    public Kind kind() {
        return kind;
    }

    public boolean interactionDeferred() {
        return kind == Kind.INTERACTION_DEFERRED;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "游戏桥接层执行失败。" : value.trim();
    }
}
