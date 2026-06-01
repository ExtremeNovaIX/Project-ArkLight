package p1.component.agent.gamer.bridge;

import p1.component.agent.gamer.adapter.core.GameActionability;

/**
 * 游戏循环探测结果。
 *
 * @param actionability    当前是否需要 RP 行动
 * @param stateFingerprint RP 可见状态摘要的稳定指纹
 */
public record GameStateProbe(
        GameActionability actionability,
        String stateFingerprint
) {
}
