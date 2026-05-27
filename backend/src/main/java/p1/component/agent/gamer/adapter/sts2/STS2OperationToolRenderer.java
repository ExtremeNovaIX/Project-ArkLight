package p1.component.agent.gamer.adapter.sts2;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolProviderResult;
import p1.component.agent.gamer.adapter.core.GameStateSnapshot;
import p1.config.mcp.MCPProperties;

import java.util.List;

/**
 * STS2 可用操作工具渲染器。
 * <p>
 * 该渲染器按单人/多人模式和当前 state_type 缩小工具列表，减少 gamer 在界面间误选工具。
 */
public class STS2OperationToolRenderer {

    private static final String MP_PREFIX = "mp_";
    private static final String TOOL_COMBAT_PLAY_CARD = "combat_play_card";
    private static final String LEGACY_TOOL_PLAY_CARD = "play_card";

    /**
     * 渲染当前状态下可用的 MCP 操作工具。
     *
     * @param tools      底层 MCP 工具集合
     * @param config     当前游戏 MCP 配置
     * @param state      最新 STS2 状态
     * @param modePrefix 自动检测出的多人模式前缀
     * @return 面向 gamer 的操作工具描述
     */
    public String render(ToolProviderResult tools,
                         MCPProperties.GameMCPConfig config,
                         GameStateSnapshot state,
                         String modePrefix) {
        StringBuilder sb = new StringBuilder();
        List<ToolSpecification> operationSpecs = tools.tools().keySet().stream()
                .filter(spec -> !isStateTool(spec.name(), config))
                .filter(spec -> isModeVisible(spec.name(), modePrefix))
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .toList();
        List<ToolSpecification> visibleSpecs = operationSpecs.stream()
                .filter(spec -> isOperationVisibleForState(spec.name(), state))
                .toList();
        if (visibleSpecs.isEmpty()) {
            visibleSpecs = operationSpecs;
        }

        visibleSpecs.forEach(spec -> {
            if (isPlayCard(spec.name())) {
                sb.append("- ")
                        .append(spec.name())
                        .append(": 打出一张手牌。args 使用 {\"card\":\"手牌名称\"}；")
                        .append("需要选择敌人的攻击牌再加 {\"target\":\"敌人 entity_id\"}；Self 目标牌不要传 target；")
                        .append("adapter 会按当前手牌自动转换为底层 MCP 参数。\n");
                return;
            }
            sb.append("- ")
                    .append(spec.name())
                    .append(": ")
                    .append(spec.description() == null ? "" : spec.description())
                    .append("\n");
        });
        return sb.isEmpty() ? "(没有可用操作工具)" : sb.toString();
    }

    /**
     * 判断工具是否属于状态读取工具。
     *
     * @param toolName 工具名
     * @param config   游戏 MCP 配置
     * @return true 表示状态读取工具
     */
    private boolean isStateTool(String toolName, MCPProperties.GameMCPConfig config) {
        String baseName = config.getStateToolName();
        return toolName != null && (toolName.equals(baseName) || toolName.equals(MP_PREFIX + baseName));
    }

    /**
     * 根据检测到的游戏模式过滤工具。
     *
     * @param toolName   工具名
     * @param modePrefix 多人模式前缀
     * @return true 表示当前模式可见
     */
    private boolean isModeVisible(String toolName, String modePrefix) {
        if (toolName == null) {
            return false;
        }
        boolean isMpTool = toolName.startsWith(MP_PREFIX);
        if (MP_PREFIX.equals(modePrefix)) {
            return isMpTool || isSharedTool(toolName);
        }
        return !isMpTool;
    }

    private boolean isSharedTool(String toolName) {
        return "menu_select".equals(toolName)
                || toolName.startsWith("deck_")
                || "get_profile".equals(toolName)
                || "list_profiles".equals(toolName)
                || "switch_profile".equals(toolName)
                || "delete_profile".equals(toolName);
    }

    /**
     * 判断工具是否属于出牌工具。
     *
     * @param toolName 工具名
     * @return true 表示出牌工具
     */
    private boolean isPlayCard(String toolName) {
        return TOOL_COMBAT_PLAY_CARD.equals(toolName)
                || LEGACY_TOOL_PLAY_CARD.equals(toolName)
                || (MP_PREFIX + TOOL_COMBAT_PLAY_CARD).equals(toolName)
                || (MP_PREFIX + LEGACY_TOOL_PLAY_CARD).equals(toolName);
    }

    /**
     * 根据 state_type 判断某个操作工具是否应该暴露。
     *
     * @param toolName 工具名
     * @param state    最新游戏状态
     * @return true 表示当前状态可见
     */
    private boolean isOperationVisibleForState(String toolName, GameStateSnapshot state) {
        if (state == null || state.json() == null) {
            return true;
        }

        String name = removeModePrefix(toolName).toLowerCase();
        String stateType = state.stateType() == null ? "" : state.stateType().toLowerCase();
        if ("card_select".equals(stateType)) {
            return isCardSelectTool(name, state.json());
        }

        JsonNode battle = state.json().path("battle");
        if (battle.isObject() && !battle.isEmpty()) {
            return isCombatTool(name);
        }

        return switch (stateType) {
            case "monster" -> isCombatTool(name);
            case "card_reward" -> isRewardPickTool(name);
            case "rewards" -> isRewardClaimTool(name);
            case "map" -> isMapTool(name);
            case "event" -> isEventTool(name);
            case "shop" -> isShopTool(name);
            case "rest", "campfire" -> isRestTool(name);
            case "chest", "treasure" -> isChestTool(name);
            default -> true;
        };
    }

    /**
     * 去掉多人模式前缀，便于按通用工具名分类。
     *
     * @param toolName 工具名
     * @return 去前缀后的工具名
     */
    private String removeModePrefix(String toolName) {
        if (toolName == null) {
            return "";
        }
        return toolName.startsWith(MP_PREFIX) ? toolName.substring(MP_PREFIX.length()) : toolName;
    }

    /**
     * 战斗行动阶段只展示战斗和药水工具。
     *
     * @param name 去前缀后的工具名
     * @return true 表示战斗可见
     */
    private boolean isCombatTool(String name) {
        return "combat_play_card".equals(name)
                || "combat_end_turn".equals(name)
                || "use_potion".equals(name);
    }

    /**
     * card_select 优先按战斗/牌堆选牌处理。
     *
     * @param name 去前缀后的工具名
     * @param root STS2 状态根节点
     * @return true 表示当前选择界面可见
     */
    private boolean isCardSelectTool(String name, JsonNode root) {
        boolean rewardLike = root.has("rewards")
                || root.has("card_reward")
                || root.path("card_select").path("reward").asBoolean(false);
        if (rewardLike) {
            return isRewardPickTool(name);
        }
        if (isDeckCardSelect(root.path("card_select"))) {
            return name.startsWith("deck_");
        }
        if (isCombatCardSelect(root.path("card_select"))) {
            return name.startsWith("combat_select") || name.startsWith("combat_confirm");
        }
        return name.startsWith("deck_");
    }

    private boolean isDeckCardSelect(JsonNode cardSelect) {
        String screenType = cardSelect.path("screen_type").asText("").toLowerCase();
        String type = cardSelect.path("type").asText("").toLowerCase();
        String prompt = cardSelect.path("prompt").asText("").toLowerCase();
        return "simple_select".equals(screenType)
                || screenType.contains("deck")
                || type.contains("deck")
                || type.contains("discard")
                || prompt.contains("抽牌堆")
                || prompt.contains("弃牌堆");
    }

    private boolean isCombatCardSelect(JsonNode cardSelect) {
        String screenType = cardSelect.path("screen_type").asText("").toLowerCase();
        String type = cardSelect.path("type").asText("").toLowerCase();
        return screenType.contains("combat") || type.contains("combat");
    }

    /**
     * 卡牌奖励界面只需要拿牌或跳过。
     *
     * @param name 去前缀后的工具名
     * @return true 表示奖励选牌工具
     */
    private boolean isRewardPickTool(String name) {
        return name.startsWith("rewards_pick")
                || name.startsWith("rewards_skip")
                || name.contains("pick_card")
                || name.contains("skip_card");
    }

    /**
     * 奖励结算界面需要领取奖励或继续到地图。
     *
     * @param name 去前缀后的工具名
     * @return true 表示奖励结算工具
     */
    private boolean isRewardClaimTool(String name) {
        return name.startsWith("rewards_")
                || name.startsWith("claim_")
                || name.contains("proceed")
                || "use_potion".equals(name);
    }

    /**
     * 地图界面只展示地图选择和继续类工具。
     *
     * @param name 去前缀后的工具名
     * @return true 表示地图工具
     */
    private boolean isMapTool(String name) {
        return name.contains("map") || name.contains("node") || name.contains("proceed");
    }

    /**
     * 事件界面只展示事件选项和继续类工具。
     *
     * @param name 去前缀后的工具名
     * @return true 表示事件工具
     */
    private boolean isEventTool(String name) {
        return name.startsWith("event_") || name.contains("proceed");
    }

    /**
     * 商店界面只展示购买、删牌和离开商店工具。
     *
     * @param name 去前缀后的工具名
     * @return true 表示商店工具
     */
    private boolean isShopTool(String name) {
        return name.startsWith("shop_") || name.contains("purchase") || name.contains("remove") || name.contains("proceed");
    }

    /**
     * 火堆界面只展示休息、锻造等火堆操作。
     *
     * @param name 去前缀后的工具名
     * @return true 表示火堆工具
     */
    private boolean isRestTool(String name) {
        return name.startsWith("rest_") || name.contains("campfire") || name.contains("proceed");
    }

    /**
     * 宝箱界面只展示领取遗物和继续类工具。
     *
     * @param name 去前缀后的工具名
     * @return true 表示宝箱工具
     */
    private boolean isChestTool(String name) {
        return name.contains("chest") || name.contains("relic") || name.contains("claim") || name.contains("proceed");
    }
}
