package p1.component.agent.rp.game.control;

/**
 * 当前 RP 游戏动作失败，需要中断本次流式响应。
 */
public class RpGameActionExecutionException extends RuntimeException {

    public enum Kind {
        FAILURE,
        INTERACTION_DEFERRED
    }

    private final String feedback;
    private final Kind kind;

    public RpGameActionExecutionException(String feedback) {
        this(feedback, Kind.FAILURE, null);
    }

    public RpGameActionExecutionException(String feedback, Throwable cause) {
        this(feedback, Kind.FAILURE, cause);
    }

    public RpGameActionExecutionException(String feedback, Kind kind, Throwable cause) {
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
        return value == null || value.isBlank() ? "RP 游戏动作执行失败。" : value.trim();
    }
}
