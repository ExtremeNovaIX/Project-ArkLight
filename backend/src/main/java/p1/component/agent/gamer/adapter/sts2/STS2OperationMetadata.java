package p1.component.agent.gamer.adapter.sts2;

import java.util.List;

/**
 * STS2 操作在入队阶段记录的语义元数据键名。
 */
final class STS2OperationMetadata {

    static final String MP_PREFIX = "mp_";
    static final String TOOL_COMBAT_PLAY_CARD = "combat_play_card";
    static final String LEGACY_TOOL_PLAY_CARD = "play_card";
    static final String TOOL_COMBAT_SELECT_CARD = "combat_select_card";
    static final String TOOL_REWARDS_PICK_CARD = "rewards_pick_card";

    static final String PLANNED_CARD_NAME = "plannedCardName";
    static final String PLANNED_TARGET_ID = "plannedTargetId";
    static final String PLANNED_OPTION_LABEL = "plannedOptionLabel";
    static final String PLANNED_MAP_NODE = "plannedMapNode";

    static final List<String> VIRTUAL_CARD_NAME_FIELDS = List.of("card", "card_name", "cardName", "name");
    static final List<String> VIRTUAL_OPTION_NAME_FIELDS = List.of(
            "option", "option_name", "optionName", "option_title", "optionTitle",
            "choice", "label", "text", "name", "title", "relic_name", "relicName");

    private STS2OperationMetadata() {
    }
}
