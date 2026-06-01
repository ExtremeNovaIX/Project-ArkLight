package p1.component.agent.gamer.trace;

import p1.component.agent.gamer.adapter.core.GameStateSnapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 为游戏状态生成短 hash，便于在复盘日志中定位状态变化而不输出完整 JSON。
 */
public final class GameStateTraceHasher {

    private static final int SHORT_HASH_LENGTH = 12;

    private GameStateTraceHasher() {
    }

    public static String shortHash(GameStateSnapshot state) {
        if (state == null || state.rawJson() == null || state.rawJson().isBlank()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(state.rawJson().getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(bytes);
            return hex.substring(0, Math.min(SHORT_HASH_LENGTH, hex.length()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
