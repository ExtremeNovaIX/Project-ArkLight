package p1.component.agent.gamer.adapter.sts2;

import com.fasterxml.jackson.databind.JsonNode;
import p1.component.agent.gamer.adapter.core.GameStateSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 渲染 RP 可见的 STS2 关键局面摘要。
 * <p>
 * RP 只需要决策语义，不直接接触执行层 index、entity_id、slot 等易漂移参数；
 * 这些底层字段仍保留在 parser 和执行层使用的 JSON 中。
 */
public class STS2StateSummaryRenderer {

    private static final Pattern DAMAGE_LABEL_PATTERN = Pattern.compile("(\\d+)(?:\\s*[xX×]\\s*(\\d+))?");

    public String render(GameStateSnapshot state) {
        if (state == null || state.json() == null) {
            return "(未能获取 STS2 状态)";
        }
        if (hasText(state.rawMarkdown())) {
            String rendered = renderNativeMarkdownForRp(state);
            return rendered.isBlank() ? "(未能获取 STS2 状态)" : rendered.trim();
        }
        String rendered = renderRpStateSummary(state);
        return rendered.isBlank() ? "(未能获取 STS2 状态)" : rendered.trim();
    }

    private String renderNativeMarkdownForRp(GameStateSnapshot state) {
        JsonNode root = state.json();
        StringBuilder sb = new StringBuilder();
        String sanitized = sanitizeNativeMarkdown(state.rawMarkdown());
        if (!sanitized.isBlank()) {
            sb.append(sanitized.trim()).append("\n\n");
        }
        appendNativePileCounts(sb, root.path("player"));
        appendDecisionFocus(sb, root);
        return sb.toString();
    }

    private String sanitizeNativeMarkdown(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String sanitized = markdown.replace("\r\n", "\n").replace('\r', '\n');
        sanitized = sanitized.replaceAll("(?m)^([ \\t]*[-*] )\\[[0-9]+\\]\\s+", "$1");
        sanitized = sanitized.replaceAll("\\s+\\(`[^`]+`\\)", "");
        sanitized = sanitized.replaceAll("Energy:\\s*([0-9]+)\\s*/\\s*[0-9]+", "Energy: $1");
        sanitized = sanitized.replaceAll("(?ms)^### Deck Information\\n.*?(?=^##\\s|\\z)", "");
        return sanitized.trim();
    }


    private void appendNativePileCounts(StringBuilder sb, JsonNode player) {
        if (!player.isObject()) {
            return;
        }
        List<String> parts = new ArrayList<>();
        addRaw(parts, prefix("draw ", text(player.path("draw_pile_count"))));
        addRaw(parts, prefix("discard ", text(player.path("discard_pile_count"))));
        addRaw(parts, prefix("exhaust ", text(player.path("exhaust_pile_count"))));
        if (parts.isEmpty()) {
            return;
        }
        sb.append("### Pile Counts\n");
        sb.append("- ").append(String.join("; ", parts)).append("\n\n");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String renderRpStateSummary(GameStateSnapshot state) {
        JsonNode root = state.json();
        StringBuilder sb = new StringBuilder();
        appendCurrentState(sb, root, state);
        appendPlayer(sb, root.path("player"));
        appendTeammates(sb, root.path("players"));
        appendEnemies(sb, root.path("battle").path("enemies"));
        appendHand(sb, root.path("player").path("hand"));
        appendEvent(sb, root.path("event"));
        appendMap(sb, root.path("map"));
        appendRewards(sb, root.path("rewards"));
        appendCardReward(sb, root.path("card_reward"));
        appendShop(sb, root.path("shop"));
        appendRestSite(sb, firstObject(root.path("rest_site"), root.path("rest"), root.path("campfire")));
        appendTreasure(sb, root.path("treasure"));
        appendSelection(sb, root);
        appendGenericOptions(sb, root.path("options"));
        appendDecisionFocus(sb, root);
        return sb.toString();
    }

    private void appendCurrentState(StringBuilder sb, JsonNode root, GameStateSnapshot state) {
        heading(sb, "当前局面");
        line(sb, "game_mode", firstNonBlank(text(root.path("game_mode")), "singleplayer"));
        line(sb, "state_type", firstNonBlank(text(root.path("state_type")), state.stateType()));
        JsonNode run = root.path("run");
        if (run.isObject()) {
            List<String> parts = new ArrayList<>();
            addRaw(parts, prefix("Act ", text(run.path("act"))));
            addRaw(parts, prefix("Floor ", text(run.path("floor"))));
            addRaw(parts, prefix("Ascension ", text(run.path("ascension"))));
            if (!parts.isEmpty()) {
                sb.append("- 进度：").append(String.join("，", parts)).append("\n");
            }
        }
        JsonNode battle = root.path("battle");
        if (battle.isObject() && !battle.isEmpty()) {
            List<String> parts = new ArrayList<>();
            addLabeled(parts, "round", text(battle.path("round")));
            addLabeled(parts, "turn", text(battle.path("turn")));
            addLabeled(parts, "is_play_phase", text(battle.path("is_play_phase")));
            addLabeled(parts, "all_players_ready", text(battle.path("all_players_ready")));
            if (!parts.isEmpty()) {
                sb.append("- 回合：").append(String.join("，", parts)).append("\n");
            }
        }
        line(sb, "message", text(root.path("message")));
        JsonNode overlay = root.path("overlay");
        if (overlay.isObject()) {
            line(sb, "overlay", joinNonBlank(text(overlay.path("screen_type")), text(overlay.path("message"))));
        }
    }

    private void appendPlayer(StringBuilder sb, JsonNode player) {
        if (!player.isObject() || player.isEmpty()) {
            return;
        }
        heading(sb, "玩家");
        sb.append("- ").append(firstNonBlank(text(player.path("character")), "Player"));
        List<String> parts = new ArrayList<>();
        addLabeled(parts, "HP", hp(player));
        addChinese(parts, "格挡", text(player.path("block")));
        addChinese(parts, "能量", text(player.path("energy")));
        addChinese(parts, "Stars", text(player.path("stars")));
        addChinese(parts, "金币", text(player.path("gold")));
        if (!parts.isEmpty()) {
            sb.append("：").append(String.join("，", parts));
        }
        sb.append("\n");
        sb.append("- 状态：").append(renderStatus(player.path("status"))).append("\n");
        appendPileCounts(sb, player);
        appendRelics(sb, player.path("relics"));
        appendPotions(sb, player.path("potions"));
        appendPets(sb, player.path("pets"));
    }

    private void appendPileCounts(StringBuilder sb, JsonNode player) {
        List<String> parts = new ArrayList<>();
        addRaw(parts, prefix("draw ", text(player.path("draw_pile_count"))));
        addRaw(parts, prefix("discard ", text(player.path("discard_pile_count"))));
        addRaw(parts, prefix("exhaust ", text(player.path("exhaust_pile_count"))));
        if (!parts.isEmpty()) {
            sb.append("- 牌堆：").append(String.join("，", parts)).append("\n");
        }
    }

    private void appendRelics(StringBuilder sb, JsonNode relics) {
        if (!relics.isArray() || relics.isEmpty()) {
            return;
        }
        sb.append("- 遗物：\n");
        for (JsonNode relic : relics) {
            List<String> parts = new ArrayList<>();
            addRaw(parts, text(relic.path("name")));
            String counter = text(relic.path("counter"));
            if (!counter.isBlank()) {
                int last = parts.size() - 1;
                if (last >= 0) {
                    parts.set(last, parts.get(last) + "[" + counter + "]");
                }
            }
            addRaw(parts, text(relic.path("description")));
            appendNestedLine(sb, renderNameDescription(parts));
        }
    }

    private void appendPotions(StringBuilder sb, JsonNode potions) {
        if (!potions.isArray() || potions.isEmpty()) {
            return;
        }
        sb.append("- 药水：\n");
        for (JsonNode potion : potions) {
            StringBuilder line = new StringBuilder(text(potion.path("name")));
            String description = text(potion.path("description"));
            if (!description.isBlank()) {
                line.append("：").append(description);
            }
            List<String> parts = new ArrayList<>();
            addEquals(parts, "combat", text(potion.path("can_use_in_combat")));
            addEquals(parts, "target", text(potion.path("target_type")));
            if (!parts.isEmpty()) {
                line.append(" ").append(String.join("，", parts));
            }
            appendNestedLine(sb, line.toString());
        }
    }

    private void appendTeammates(StringBuilder sb, JsonNode players) {
        if (!players.isArray() || players.isEmpty()) {
            return;
        }
        List<JsonNode> teammates = new ArrayList<>();
        for (JsonNode player : players) {
            if (player.path("is_local").isBoolean() && player.path("is_local").asBoolean()) {
                continue;
            }
            teammates.add(player);
        }
        if (teammates.isEmpty()) {
            return;
        }
        heading(sb, "队友");
        for (JsonNode player : teammates) {
            sb.append("- ").append(firstNonBlank(text(player.path("character")), "Player"));
            List<String> parts = new ArrayList<>();
            addLabeled(parts, "HP", hp(player));
            addChinese(parts, "格挡", text(player.path("block")));
            addChinese(parts, "金币", text(player.path("gold")));
            addEquals(parts, "alive", text(player.path("is_alive")));
            addEquals(parts, "ready", text(player.path("is_ready_to_end_turn")));
            if (!parts.isEmpty()) {
                sb.append("：").append(String.join("，", parts));
            }
            sb.append("\n");
            appendPets(sb, player.path("pets"));
        }
    }

    private void appendPets(StringBuilder sb, JsonNode pets) {
        if (!pets.isArray() || pets.isEmpty()) {
            return;
        }
        for (JsonNode pet : pets) {
            List<String> parts = new ArrayList<>();
            addLabeled(parts, "HP", hp(pet));
            addChinese(parts, "格挡", text(pet.path("block")));
            addEquals(parts, "alive", text(pet.path("alive")));
            sb.append("  - ").append(firstNonBlank(text(pet.path("name")), "Pet"));
            if (!parts.isEmpty()) {
                sb.append("：").append(String.join("，", parts));
            }
            sb.append("\n");
        }
    }

    private void appendEnemies(StringBuilder sb, JsonNode enemies) {
        if (!enemies.isArray() || enemies.isEmpty()) {
            return;
        }
        heading(sb, "敌人");
        for (JsonNode enemy : enemies) {
            sb.append("- ").append(firstNonBlank(text(enemy.path("name")), "Enemy"));
            List<String> parts = new ArrayList<>();
            addLabeled(parts, "HP", hp(enemy));
            addChinese(parts, "格挡", text(enemy.path("block")));
            if (!parts.isEmpty()) {
                sb.append("：").append(String.join("，", parts));
            }
            sb.append("\n");
            appendIntentLines(sb, enemy.path("intents"));
            String status = renderStatus(enemy.path("status"));
            if (!"无".equals(status)) {
                sb.append("  - status：").append(status).append("\n");
            }
        }
    }

    private void appendIntentLines(StringBuilder sb, JsonNode intents) {
        if (!intents.isArray() || intents.isEmpty()) {
            return;
        }
        for (JsonNode intent : intents) {
            String main = joinNonBlank(text(intent.path("type")), text(intent.path("label")));
            String detail = firstNonBlank(text(intent.path("description")), text(intent.path("title")));
            sb.append("  - intent：").append(main);
            if (!detail.isBlank()) {
                sb.append("；").append(detail);
            }
            sb.append("\n");
        }
    }

    private void appendHand(StringBuilder sb, JsonNode hand) {
        if (!hand.isArray() || hand.isEmpty()) {
            return;
        }
        heading(sb, "手牌");
        for (JsonNode card : hand) {
            sb.append("- ").append(formatCard(card, true)).append("\n");
        }
    }

    private void appendEvent(StringBuilder sb, JsonNode event) {
        if (!event.isObject() || event.isEmpty()) {
            return;
        }
        heading(sb, "事件");
        line(sb, "event_id", text(event.path("event_id")));
        line(sb, "event_name", text(event.path("event_name")));
        line(sb, "is_ancient", text(event.path("is_ancient")));
        line(sb, "in_dialogue", text(event.path("in_dialogue")));
        line(sb, "body", text(event.path("body")));
        JsonNode options = event.path("options");
        if (options.isArray() && !options.isEmpty()) {
            sb.append("- 选项：\n");
            for (JsonNode option : options) {
                appendNestedLine(sb, formatEventOption(option));
            }
        }
        line(sb, "is_shared", text(event.path("is_shared")));
        appendVotes(sb, event.path("votes"));
        line(sb, "all_voted", text(event.path("all_voted")));
    }

    private String formatEventOption(JsonNode option) {
        String title = firstNonBlank(text(option.path("title")), text(option.path("name")), text(option.path("label")), "option");
        StringBuilder line = new StringBuilder(title);
        String description = text(option.path("description"));
        if (!description.isBlank()) {
            line.append("：").append(description);
        }
        List<String> parts = new ArrayList<>();
        addEquals(parts, "locked", text(option.path("is_locked")));
        addEquals(parts, "proceed", text(option.path("is_proceed")));
        String relicName = text(option.path("relic_name"));
        if (!relicName.isBlank()) {
            String relicDescription = text(option.path("relic_description"));
            addRaw(parts, "relic=" + relicName + (relicDescription.isBlank() ? "" : "：" + relicDescription));
        }
        if (!parts.isEmpty()) {
            line.append("，").append(String.join("，", parts));
        }
        return line.toString();
    }

    private void appendMap(StringBuilder sb, JsonNode map) {
        if (!map.isObject() || map.isEmpty()) {
            return;
        }
        heading(sb, "地图");
        String current = formatMapNode(map.path("current_position"), false);
        if (!current.isBlank()) {
            sb.append("- 当前位置：").append(current).append("\n");
        }
        JsonNode options = map.path("next_options");
        if (options.isArray() && !options.isEmpty()) {
            sb.append("- 可选节点：\n");
            for (JsonNode option : options) {
                appendNestedLine(sb, formatMapNode(option, true));
            }
        }
        JsonNode boss = map.path("boss");
        String bossCoordinate = coordinate(boss);
        if (!bossCoordinate.isBlank()) {
            sb.append("- boss：").append(bossCoordinate).append("\n");
        }
        appendVotes(sb, map.path("votes"));
        line(sb, "all_voted", text(map.path("all_voted")));
    }

    private String formatMapNode(JsonNode node, boolean includeLeadsTo) {
        if (!node.isObject()) {
            return "";
        }
        String type = text(node.path("type"));
        String coordinate = coordinate(node);
        if (type.isBlank() && coordinate.isBlank()) {
            return "";
        }
        StringBuilder line = new StringBuilder();
        if (!type.isBlank()) {
            line.append(type);
        }
        if (!coordinate.isBlank()) {
            if (line.isEmpty()) {
                line.append(coordinate);
            } else if (includeLeadsTo) {
                line.append("，坐标 ").append(coordinate);
            } else {
                line.insert(0, coordinate + "，");
            }
        }
        if (includeLeadsTo) {
            List<String> leadsTo = mapNodeTypes(node.path("leads_to"));
            if (!leadsTo.isEmpty()) {
                line.append("，后续 ").append(String.join(" / ", leadsTo));
            }
        }
        return line.toString();
    }

    private List<String> mapNodeTypes(JsonNode nodes) {
        List<String> types = new ArrayList<>();
        if (!nodes.isArray()) {
            return types;
        }
        for (JsonNode node : nodes) {
            String type = text(node.path("type"));
            if (!type.isBlank()) {
                types.add(type);
            }
        }
        return types;
    }

    private void appendVotes(StringBuilder sb, JsonNode votes) {
        if (!votes.isArray() || votes.isEmpty()) {
            return;
        }
        sb.append("- 投票：\n");
        for (JsonNode vote : votes) {
            StringBuilder line = new StringBuilder(firstNonBlank(text(vote.path("player")), "Player"));
            List<String> parts = new ArrayList<>();
            addEquals(parts, "voted", text(vote.path("voted")));
            String coordinate = voteCoordinate(vote);
            if (!coordinate.isBlank()) {
                addEquals(parts, "vote", coordinate);
            }
            String option = text(vote.path("vote_option"));
            if (!option.isBlank()) {
                addEquals(parts, "option", option);
            }
            if (!parts.isEmpty()) {
                line.append("：").append(String.join("，", parts));
            }
            appendNestedLine(sb, line.toString());
        }
    }

    private void appendRewards(StringBuilder sb, JsonNode rewards) {
        if (!rewards.isObject() || rewards.isEmpty()) {
            return;
        }
        JsonNode items = rewards.path("items");
        if (items.isArray() && !items.isEmpty()) {
            heading(sb, "奖励");
            for (JsonNode item : items) {
                sb.append("- ").append(formatRewardItem(item)).append("\n");
            }
        }
    }

    private String formatRewardItem(JsonNode item) {
        String type = firstNonBlank(text(item.path("type")), "reward");
        List<String> parts = new ArrayList<>();
        addRaw(parts, text(item.path("description")));
        addChinese(parts, "gold", text(item.path("gold_amount")));
        addLabeled(parts, "potion", text(item.path("potion_name")));
        return type + (parts.isEmpty() ? "" : "：" + String.join("，", parts));
    }

    private void appendCardReward(StringBuilder sb, JsonNode cardReward) {
        if (!cardReward.isObject() || cardReward.isEmpty()) {
            return;
        }
        JsonNode cards = cardReward.path("cards");
        if (cards.isArray() && !cards.isEmpty()) {
            heading(sb, "可选奖励牌");
            for (JsonNode card : cards) {
                sb.append("- ").append(formatCard(card, false)).append("\n");
            }
        }
    }

    private void appendShop(StringBuilder sb, JsonNode shop) {
        if (!shop.isObject() || shop.isEmpty()) {
            return;
        }
        JsonNode items = shop.path("items");
        if (!items.isArray() || items.isEmpty()) {
            line(sb, "shop_error", text(shop.path("error")));
            return;
        }
        heading(sb, "商店");
        for (JsonNode item : items) {
            sb.append("- ").append(formatShopItem(item)).append("\n");
        }
        line(sb, "shop_error", text(shop.path("error")));
    }

    private String formatShopItem(JsonNode item) {
        String category = firstNonBlank(text(item.path("category")), "item");
        String name = switch (category) {
            case "card" -> text(item.path("card_name"));
            case "relic" -> text(item.path("relic_name"));
            case "potion" -> text(item.path("potion_name"));
            default -> firstNonBlank(text(item.path("name")), text(item.path("title")));
        };
        StringBuilder line = new StringBuilder(category);
        if (!name.isBlank()) {
            line.append(" ").append(name);
        }
        List<String> parts = new ArrayList<>();
        addLabeled(parts, "price", text(item.path("price")));
        addEquals(parts, "stocked", text(item.path("is_stocked")));
        addEquals(parts, "afford", text(item.path("can_afford")));
        addRaw(parts, prefix("cost ", firstNonBlank(text(item.path("card_cost")), text(item.path("cost")))));
        addRaw(parts, firstNonBlank(text(item.path("card_type")), text(item.path("type"))));
        addRaw(parts, firstNonBlank(text(item.path("card_rarity")), text(item.path("rarity"))));
        if (!parts.isEmpty()) {
            line.append("：").append(String.join("，", parts));
        }
        String description = firstNonBlank(
                text(item.path("card_description")),
                text(item.path("relic_description")),
                text(item.path("potion_description")),
                text(item.path("description")));
        if (!description.isBlank()) {
            line.append("。").append(description);
        }
        return line.toString();
    }

    private void appendRestSite(StringBuilder sb, JsonNode restSite) {
        if (!restSite.isObject() || restSite.isEmpty()) {
            return;
        }
        JsonNode options = restSite.path("options");
        if (!options.isArray() || options.isEmpty()) {
            return;
        }
        heading(sb, "休息点");
        for (JsonNode option : options) {
            String name = firstNonBlank(text(option.path("name")), text(option.path("title")), text(option.path("label")), text(option.path("id")), "option");
            StringBuilder line = new StringBuilder(name);
            String description = text(option.path("description"));
            if (!description.isBlank()) {
                line.append("：").append(description);
            }
            String enabled = firstNonBlank(text(option.path("is_enabled")), text(option.path("enabled")));
            if (!enabled.isBlank()) {
                line.append("，enabled=").append(enabled);
            }
            sb.append("- ").append(line).append("\n");
        }
    }

    private void appendTreasure(StringBuilder sb, JsonNode treasure) {
        if (!treasure.isObject() || treasure.isEmpty()) {
            return;
        }
        heading(sb, "宝箱");
        line(sb, "message", text(treasure.path("message")));
        JsonNode relics = treasure.path("relics");
        if (relics.isArray() && !relics.isEmpty()) {
            for (JsonNode relic : relics) {
                StringBuilder line = new StringBuilder(firstNonBlank(text(relic.path("name")), "Relic"));
                List<String> parts = new ArrayList<>();
                addRaw(parts, text(relic.path("rarity")));
                if (!parts.isEmpty()) {
                    line.append("：").append(String.join("，", parts));
                }
                String description = text(relic.path("description"));
                if (!description.isBlank()) {
                    line.append("。").append(description);
                }
                sb.append("- ").append(line).append("\n");
            }
        }
        line(sb, "is_bidding_phase", text(treasure.path("is_bidding_phase")));
        appendBids(sb, treasure.path("bids"));
        line(sb, "all_bid", text(treasure.path("all_bid")));
    }

    private void appendBids(StringBuilder sb, JsonNode bids) {
        if (!bids.isArray() || bids.isEmpty()) {
            return;
        }
        sb.append("- bids：\n");
        for (JsonNode bid : bids) {
            String player = firstNonBlank(text(bid.path("player")), "Player");
            String voted = text(bid.path("voted"));
            appendNestedLine(sb, player + (voted.isBlank() ? "" : "：voted=" + voted));
        }
    }

    private void appendSelection(StringBuilder sb, JsonNode root) {
        JsonNode selection = firstObject(root.path("hand_select"), root.path("card_select"), root.path("bundle_select"), root.path("relic_select"), root.path("crystal_sphere"));
        if (!selection.isObject() || selection.isEmpty()) {
            return;
        }
        heading(sb, "选牌");
        line(sb, "screen_type", text(selection.path("screen_type")));
        line(sb, "mode", text(selection.path("mode")));
        line(sb, "prompt", text(selection.path("prompt")));
        line(sb, "preview_showing", text(selection.path("preview_showing")));
        line(sb, "can_confirm", text(selection.path("can_confirm")));
        line(sb, "can_cancel", text(selection.path("can_cancel")));
        line(sb, "can_skip", text(selection.path("can_skip")));
        JsonNode cards = selection.path("cards");
        if (cards.isArray() && !cards.isEmpty()) {
            sb.append("- cards：\n");
            for (JsonNode card : cards) {
                appendNestedLine(sb, formatCard(card, false));
            }
        }
        JsonNode selectedCards = selection.path("selected_cards");
        if (selectedCards.isArray() && !selectedCards.isEmpty()) {
            sb.append("- selected_cards：\n");
            for (JsonNode card : selectedCards) {
                appendNestedLine(sb, firstNonBlank(text(card.path("name")), "card"));
            }
        }
        appendBundles(sb, selection.path("bundles"));
        appendRelicChoices(sb, selection.path("relics"));
    }

    private void appendBundles(StringBuilder sb, JsonNode bundles) {
        if (!bundles.isArray() || bundles.isEmpty()) {
            return;
        }
        sb.append("- bundles：\n");
        for (JsonNode bundle : bundles) {
            List<String> cards = new ArrayList<>();
            JsonNode cardNodes = bundle.path("cards");
            if (cardNodes.isArray()) {
                for (JsonNode card : cardNodes) {
                    cards.add(formatCard(card, false));
                }
            }
            appendNestedLine(sb, "cards=" + String.join(" | ", cards));
        }
    }

    private void appendRelicChoices(StringBuilder sb, JsonNode relics) {
        if (!relics.isArray() || relics.isEmpty()) {
            return;
        }
        sb.append("- relics：\n");
        for (JsonNode relic : relics) {
            appendNestedLine(sb, renderNameDescription(List.of(
                    text(relic.path("name")),
                    text(relic.path("rarity")),
                    text(relic.path("description"))
            )));
        }
    }

    private void appendGenericOptions(StringBuilder sb, JsonNode options) {
        if (!options.isArray() || options.isEmpty()) {
            return;
        }
        heading(sb, "选项");
        for (JsonNode option : options) {
            if (option.isTextual()) {
                sb.append("- ").append(option.asText()).append("\n");
            } else if (option.isObject()) {
                sb.append("- ").append(firstNonBlank(text(option.path("name")), text(option.path("title")), text(option.path("label")), text(option.path("option")), "option"));
                String enabled = firstNonBlank(text(option.path("enabled")), text(option.path("is_enabled")));
                if (!enabled.isBlank()) {
                    sb.append("，enabled=").append(enabled);
                }
                sb.append("\n");
            }
        }
    }

    private void appendDecisionFocus(StringBuilder sb, JsonNode root) {
        List<String> lines = new ArrayList<>();
        JsonNode player = root.path("player");
        int incomingDamage = incomingAttackDamage(root.path("battle").path("enemies"));
        if (incomingDamage >= 0) {
            lines.add("敌方即将造成伤害：" + incomingDamage);
            Integer block = intValue(player.path("block"));
            if (block != null) {
                lines.add("当前格挡缺口：" + Math.max(0, incomingDamage - block));
            }
        }
        String energy = text(player.path("energy"));
        if (!energy.isBlank()) {
            lines.add("当前可用能量：" + energy);
        }
        if (player.isObject()) {
            String hp = hp(player);
            if (!hp.isBlank() && !root.has("battle")) {
                lines.add("当前生命：" + hp);
            }
            String gold = text(player.path("gold"));
            if (!gold.isBlank() && !root.has("battle")) {
                lines.add("当前金币：" + gold);
            }
        }
        appendUnplayableFocus(lines, player.path("hand"));
        JsonNode cardReward = root.path("card_reward");
        if (cardReward.isObject()) {
            String canSkip = text(cardReward.path("can_skip"));
            if (!canSkip.isBlank()) {
                lines.add("可以跳过奖励：" + canSkip);
            }
        }
        appendMapFocus(lines, root.path("map"));
        if (lines.isEmpty()) {
            return;
        }
        heading(sb, "本步决策重点");
        for (String line : lines) {
            sb.append("- ").append(line).append("\n");
        }
    }

    private void appendUnplayableFocus(List<String> lines, JsonNode hand) {
        if (!hand.isArray() || hand.isEmpty()) {
            return;
        }
        List<String> unplayable = new ArrayList<>();
        for (JsonNode card : hand) {
            if (card.path("can_play").isBoolean() && !card.path("can_play").asBoolean()) {
                String name = firstNonBlank(text(card.path("name")), "card");
                String reason = text(card.path("unplayable_reason"));
                unplayable.add(name + (reason.isBlank() ? "" : "(" + reason + ")"));
            }
        }
        lines.add("不可打的牌：" + (unplayable.isEmpty() ? "无" : String.join("，", unplayable)));
    }

    private void appendMapFocus(List<String> lines, JsonNode map) {
        if (!map.isObject()) {
            return;
        }
        JsonNode options = map.path("next_options");
        if (options.isArray() && !options.isEmpty()) {
            Set<String> types = new LinkedHashSet<>();
            for (JsonNode option : options) {
                String type = text(option.path("type"));
                if (!type.isBlank()) {
                    types.add(type);
                }
            }
            if (!types.isEmpty()) {
                lines.add("可选节点类型：" + String.join("，", types));
            }
        }
        JsonNode votes = map.path("votes");
        if (votes.isArray() && !votes.isEmpty()) {
            int voted = 0;
            for (JsonNode vote : votes) {
                if (vote.path("voted").asBoolean(false)) {
                    voted++;
                }
            }
            lines.add("已投票人数：" + voted + "/" + votes.size());
        }
    }

    private String formatCard(JsonNode card, boolean includePlayability) {
        StringBuilder line = new StringBuilder(firstNonBlank(text(card.path("name")), text(card.path("card_name")), "Card"));
        List<String> parts = new ArrayList<>();
        addRaw(parts, prefix("cost ", firstNonBlank(text(card.path("cost")), text(card.path("card_cost")))));
        addRaw(parts, prefix("star_cost ", firstNonBlank(text(card.path("star_cost")), text(card.path("card_star_cost")))));
        addRaw(parts, firstNonBlank(text(card.path("type")), text(card.path("card_type"))));
        addRaw(parts, firstNonBlank(text(card.path("rarity")), text(card.path("card_rarity"))));
        String targetType = text(card.path("target_type"));
        if (!targetType.isBlank()) {
            addLabeled(parts, "target", targetType);
        }
        if (includePlayability) {
            addLabeled(parts, "can_play", text(card.path("can_play")));
            String reason = text(card.path("unplayable_reason"));
            if (!reason.isBlank()) {
                parts.add("unplayable_reason " + reason);
            }
        }
        if (!parts.isEmpty()) {
            line.append("：").append(String.join("，", parts));
        }
        String description = firstNonBlank(text(card.path("description")), text(card.path("card_description")));
        if (!description.isBlank()) {
            line.append("。").append(description);
        }
        return line.toString();
    }

    private int incomingAttackDamage(JsonNode enemies) {
        if (!enemies.isArray() || enemies.isEmpty()) {
            return -1;
        }
        int total = 0;
        boolean found = false;
        for (JsonNode enemy : enemies) {
            JsonNode intents = enemy.path("intents");
            if (!intents.isArray()) {
                continue;
            }
            for (JsonNode intent : intents) {
                String type = text(intent.path("type")).toLowerCase();
                if (!type.contains("attack")) {
                    continue;
                }
                int damage = parseDamage(text(intent.path("label")));
                if (damage >= 0) {
                    total += damage;
                    found = true;
                }
            }
        }
        return found ? total : -1;
    }

    private int parseDamage(String label) {
        if (label == null || label.isBlank()) {
            return -1;
        }
        Matcher matcher = DAMAGE_LABEL_PATTERN.matcher(label);
        if (!matcher.find()) {
            return -1;
        }
        int base = Integer.parseInt(matcher.group(1));
        String multiplier = matcher.group(2);
        if (multiplier != null && !multiplier.isBlank()) {
            return base * Integer.parseInt(multiplier);
        }
        return base;
    }

    private String renderStatus(JsonNode status) {
        if (!status.isArray() || status.isEmpty()) {
            return "无";
        }
        List<String> values = new ArrayList<>();
        for (JsonNode power : status) {
            String name = firstNonBlank(text(power.path("name")), text(power.path("id")));
            String amount = text(power.path("amount"));
            if (!name.isBlank()) {
                values.add(amount.isBlank() ? name : name + " " + amount);
            }
        }
        return values.isEmpty() ? "无" : String.join("，", values);
    }

    private String voteCoordinate(JsonNode vote) {
        String col = text(vote.path("vote_col"));
        String row = text(vote.path("vote_row"));
        if (col.isBlank() || row.isBlank()) {
            return "";
        }
        return "(" + col + "," + row + ")";
    }

    private String coordinate(JsonNode node) {
        if (!node.isObject()) {
            return "";
        }
        String col = text(node.path("col"));
        String row = text(node.path("row"));
        if (col.isBlank() || row.isBlank()) {
            return "";
        }
        return "(" + col + "," + row + ")";
    }

    private String hp(JsonNode node) {
        String hp = text(node.path("hp"));
        String maxHp = text(node.path("max_hp"));
        if (hp.isBlank() && maxHp.isBlank()) {
            return "";
        }
        if (maxHp.isBlank()) {
            return hp;
        }
        return hp + "/" + maxHp;
    }

    private Integer intValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isInt() || node.isLong()) {
            return node.asInt();
        }
        try {
            return Integer.parseInt(node.asText());
        } catch (Exception ignored) {
            return null;
        }
    }

    private JsonNode firstObject(JsonNode... nodes) {
        if (nodes == null) {
            return null;
        }
        for (JsonNode node : nodes) {
            if (node != null && node.isObject() && !node.isEmpty()) {
                return node;
            }
        }
        return nodes.length == 0 ? null : nodes[0];
    }

    private void heading(StringBuilder sb, String heading) {
        if (!sb.isEmpty()) {
            sb.append("\n");
        }
        sb.append("## ").append(heading).append("\n\n");
    }

    private void line(StringBuilder sb, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append("- ").append(key).append("：").append(value).append("\n");
    }

    private void appendNestedLine(StringBuilder sb, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append("  - ").append(value).append("\n");
    }

    private String renderNameDescription(List<String> parts) {
        List<String> values = new ArrayList<>(parts);
        values.removeIf(value -> value == null || value.isBlank());
        if (values.isEmpty()) {
            return "";
        }
        if (values.size() == 1) {
            return values.getFirst();
        }
        return values.getFirst() + "：" + String.join("，", values.subList(1, values.size()));
    }

    private String joinNonBlank(String... values) {
        List<String> parts = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                addRaw(parts, value);
            }
        }
        return String.join(" ", parts);
    }

    private String prefix(String prefix, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return prefix + value;
    }

    private void addChinese(List<String> parts, String key, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(key + " " + value);
        }
    }

    private void addLabeled(List<String> parts, String key, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(key + " " + value);
        }
    }

    private void addEquals(List<String> parts, String key, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(key + "=" + value);
        }
    }

    private void addRaw(List<String> parts, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(value.trim());
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText("");
        }
        return node.asText(node.toString());
    }
}
