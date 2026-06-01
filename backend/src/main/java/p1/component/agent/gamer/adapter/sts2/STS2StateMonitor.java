package p1.component.agent.gamer.adapter.sts2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import p1.component.agent.gamer.adapter.core.GameBridgeException;
import p1.component.agent.gamer.adapter.core.GameStateSnapshot;
import p1.component.agent.gamer.adapter.core.QueuedGameOperation;

import java.util.ArrayList;
import java.util.List;

/**
 * STS2 操作执行后状态监控。
 * <p>
 * 当状态类型推进或手牌数量出现异常变化时，后续队列可能基于脏状态，必须立即中断。
 */
public class STS2StateMonitor {

    private static final String TOOL_COMBAT_PLAY_CARD = "combat_play_card";
    private static final String LEGACY_TOOL_PLAY_CARD = "play_card";
    private static final String MP_PREFIX = "mp_";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void monitorAfterExecute(QueuedGameOperation operation,
                                    GameStateSnapshot beforeState,
                                    GameStateSnapshot afterState,
                                    String toolResult) {
        String mcpError = extractToolError(toolResult);
        if (mcpError != null) {
            throw new GameBridgeException("MCP 工具执行失败: " + mcpError);
        }

        if (!safeEquals(beforeState.stateType(), afterState.stateType())) {
            throw new GameBridgeException(
                    "STS2 state_type 从 " + beforeState.stateType() + " 变为 " + afterState.stateType(),
                    GameBridgeException.Kind.STATE_ADVANCED);
        }

        if (isRewardPickCard(operation.request().name())) {
            JsonNode beforeCards = cardRewardCards(beforeState);
            JsonNode afterCards = cardRewardCards(afterState);
            if ("card_reward".equalsIgnoreCase(beforeState.stateType())
                    && "card_reward".equalsIgnoreCase(afterState.stateType())
                    && beforeCards.isArray()
                    && !beforeCards.isEmpty()
                    && beforeCards.toString().equals(afterCards.toString())) {
                throw new GameBridgeException("STS2 奖励选牌后候选卡牌没有变化，疑似未真正选中；当前奖励牌: "
                        + renderHandNames(afterCards));
            }
        }

        if (isPlayCard(operation.request().name())) {
            int beforeHand = hand(beforeState).size();
            int afterHand = hand(afterState).size();
            if (afterHand != beforeHand - 1) {
                String message = "STS2 出牌后手牌数量变化不符合预期：执行前 " + beforeHand + "，" +
                        "执行后 " + afterHand + "，" +
                        "后续状态基于旧手牌不可靠；" +
                        "出现问题时执行的操作：" + operation.request().name();
                throw new GameBridgeException(message);
            }
        }
    }

    private String extractToolError(String toolResult) {
        if (toolResult == null || toolResult.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(toolResult);
            if ("error".equals(root.path("status").asText(""))) {
                return root.path("error").asText("未知错误");
            }
        } catch (Exception ignored) {
            // 非 JSON 结果由通用队列执行层按文本错误处理。
        }
        return null;
    }

    private boolean isPlayCard(String toolName) {
        return TOOL_COMBAT_PLAY_CARD.equals(toolName)
                || LEGACY_TOOL_PLAY_CARD.equals(toolName)
                || (MP_PREFIX + TOOL_COMBAT_PLAY_CARD).equals(toolName)
                || (MP_PREFIX + LEGACY_TOOL_PLAY_CARD).equals(toolName);
    }

    private boolean isRewardPickCard(String toolName) {
        String name = baseToolName(toolName);
        return "rewards_pick_card".equals(name);
    }

    private String baseToolName(String toolName) {
        if (toolName == null) {
            return "";
        }
        return toolName.startsWith(MP_PREFIX) ? toolName.substring(MP_PREFIX.length()) : toolName;
    }

    private JsonNode hand(GameStateSnapshot state) {
        if (state == null || state.json() == null) {
            return objectMapper.createArrayNode();
        }
        return state.json().path("player").path("hand");
    }

    private JsonNode cardRewardCards(GameStateSnapshot state) {
        if (state == null || state.json() == null) {
            return objectMapper.createArrayNode();
        }
        return state.json().path("card_reward").path("cards");
    }

    private String renderHandNames(JsonNode hand) {
        if (!hand.isArray() || hand.isEmpty()) {
            return "(空)";
        }
        List<String> names = new ArrayList<>();
        for (JsonNode card : hand) {
            names.add(card.path("name").asText("(未知牌)"));
        }
        return String.join(", ", names);
    }

    private boolean safeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
