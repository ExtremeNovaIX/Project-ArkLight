package p1.component.agent.gamer.bridge;

/**
 * gamer 在每个 ACTION 中附带的表达欲候选。
 * <p>
 * 该对象只保存模型声明的原始表达意图，是否真的交给 RP 开口由后续决策层判断。
 *
 * @param score        模型自评表达欲，0-100
 * @param type         表达类型，例如 self_talk、risk、replan、regret、choice、confidence
 * @param urgency      表达紧急度，例如 silent、defer、soon、immediate
 * @param innerThought 可转交 RP 的内心活动，不是台词
 * @param reason       模型认为自己想表达的原因
 */
public record GameExpressionPayload(
        int score,
        String type,
        String urgency,
        String innerThought,
        String reason
) {

    /**
     * 空表达候选。
     *
     * @return 分数为 0 的表达候选
     */
    public static GameExpressionPayload empty() {
        return new GameExpressionPayload(0, "", "", "", "");
    }
}
