package p1.component.agent.gamer.adapter.sts2;

import com.fasterxml.jackson.databind.JsonNode;
import p1.component.agent.gamer.adapter.core.GameAdapterContext;
import p1.component.agent.gamer.adapter.core.GameStateSnapshot;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * STS2 单人/多人模式识别器。
 * <p>
 * 多人 run 的最高优先级证据是原始 JSON 中的 game_mode=multiplayer；菜单和 lobby 字段只用于运行前界面。
 */
public final class STS2ModeDetector {

    public static final String MP_PREFIX = "mp_";

    private static final Pattern MULTIPLAYER_GAME_MODE_PATTERN =
            Pattern.compile("\"game_mode\"\\s*:\\s*\"multiplayer\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern SINGLEPLAYER_GAME_MODE_PATTERN =
            Pattern.compile("\"game_mode\"\\s*:\\s*\"singleplayer\"", Pattern.CASE_INSENSITIVE);

    private final ConcurrentHashMap<String, String> sessionModePrefixes = new ConcurrentHashMap<>();

    /**
     * 根据当前状态判断 MCP 工具名前缀。
     *
     * @param state 最新 STS2 状态
     * @return 多人返回 mp_，单人或未知返回空字符串
     */
    public String modePrefix(GameStateSnapshot state) {
        JsonNode root = state == null ? null : state.json();
        if (root == null || root.isMissingNode()) {
            return "";
        }

        if (hasMultiplayerGameMode(state)) {
            return MP_PREFIX;
        }

        String gameMode = root.path("game_mode").asText("");
        if ("multiplayer".equalsIgnoreCase(gameMode)) {
            return MP_PREFIX;
        }
        if ("singleplayer".equalsIgnoreCase(gameMode)) {
            return "";
        }

        String menuScreen = root.path("menu_screen").asText("");
        if (menuScreen.toLowerCase(Locale.ROOT).startsWith("multiplayer")) {
            return MP_PREFIX;
        }

        JsonNode lobby = root.path("lobby");
        if (lobby.isObject()) {
            String lobbyType = lobby.path("type").asText("");
            if ("singleplayer".equalsIgnoreCase(lobbyType)) {
                return "";
            }
            if (!lobbyType.isBlank()
                    || lobby.has("players")
                    || lobby.has("player_count")
                    || lobby.has("all_ready")
                    || lobby.has("is_local_ready")
                    || lobby.has("local_player_id")) {
                return MP_PREFIX;
            }
        }

        if (root.has("local_player_slot") || root.has("player_count") || root.has("net_type")) {
            return MP_PREFIX;
        }
        if (hasOption(root.path("options"), "unready")) {
            return MP_PREFIX;
        }
        return "";
    }

    /**
     * 使用当前状态判断模式；状态缺少模式线索时，只回退到同一 session 最近一次 fetch 观察到的模式。
     */
    public String modePrefix(GameAdapterContext context, GameStateSnapshot state) {
        String detected = modePrefix(state);
        if (MP_PREFIX.equals(detected) || isExplicitSingleplayer(state)) {
            return detected;
        }
        String sessionId = context == null ? null : context.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return detected;
        }
        return sessionModePrefixes.getOrDefault(sessionId, detected);
    }

    /**
     * 记录一次成功 fetch 得到的模式。非多人状态会清理该 session 的多人缓存，避免同一 session 串模式。
     */
    public void remember(GameAdapterContext context, GameStateSnapshot state) {
        String sessionId = context == null ? null : context.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (MP_PREFIX.equals(modePrefix(state))) {
            sessionModePrefixes.put(sessionId, MP_PREFIX);
        } else {
            sessionModePrefixes.remove(sessionId);
        }
    }

    /**
     * 判断状态是否明确是多人模式。
     *
     * @param state 最新 STS2 状态
     * @return true 表示状态明确属于多人模式
     */
    public boolean isMultiplayer(GameStateSnapshot state) {
        return MP_PREFIX.equals(modePrefix(state));
    }

    private boolean hasMultiplayerGameMode(GameStateSnapshot state) {
        return state != null
                && state.rawJson() != null
                && MULTIPLAYER_GAME_MODE_PATTERN.matcher(state.rawJson()).find();
    }

    private boolean hasSingleplayerGameMode(GameStateSnapshot state) {
        return state != null
                && state.rawJson() != null
                && SINGLEPLAYER_GAME_MODE_PATTERN.matcher(state.rawJson()).find();
    }

    private boolean isExplicitSingleplayer(GameStateSnapshot state) {
        JsonNode root = state == null ? null : state.json();
        if (root == null || root.isMissingNode()) {
            return false;
        }
        if (hasSingleplayerGameMode(state)) {
            return true;
        }
        if ("singleplayer".equalsIgnoreCase(root.path("game_mode").asText(""))) {
            return true;
        }
        return "singleplayer".equalsIgnoreCase(root.path("lobby").path("type").asText(""));
    }

    private boolean hasOption(JsonNode options, String name) {
        if (options == null || !options.isArray() || name == null || name.isBlank()) {
            return false;
        }
        for (JsonNode option : options) {
            String optionName = option.isTextual() ? option.asText("") : option.path("name").asText("");
            if (name.equalsIgnoreCase(optionName)) {
                return true;
            }
        }
        return false;
    }
}
