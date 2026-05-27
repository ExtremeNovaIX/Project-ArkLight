package p1.component.agent.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 测试 router 小模型从播音风格描述中提取 emotion + vox 标签的准确率。
 * <p>
 * 直接向 llama.cpp server 发送自定义 prompt，绕过通用路由系统提示词。
 * 如果服务不可用，测试会被跳过。
 */
class VoxCpm2RouterAccuracyTest {

    private static final String ENDPOINT = "http://127.0.0.1:8087/v1/chat/completions";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // ─── 测试样本 (风格描述, 期望emotion, 期望vox_tag) ───────────
    private static final String[][] TEST_CASES = {
            // HAPPY / laughing
            {"面带微笑，声音甜美温柔", "HAPPY", "laughing"},
            {"欢快活泼，节奏明快", "HAPPY", "laughing"},
            {"语气欣喜，忍不住笑出声", "HAPPY", "laughing"},
            {"温柔地轻笑，声音暖暖的", "HAPPY", "laughing"},
            // SIGH / sigh
            {"语调低落，无奈地叹了口气", "SIGH", "sigh"},
            {"语气消沉，声音沙哑疲惫", "SIGH", "sigh"},
            {"忧郁地呢喃，声音带着哭腔", "SIGH", "sigh"},
            {"惆怅地望向远方，声音落寞", "SIGH", "sigh"},
            // SURPRISE / Surprise-wa
            {"震惊得说不出话，语调上扬", "SURPRISE", "Surprise-wa"},
            {"大吃一惊，声音颤抖", "SURPRISE", "Surprise-wa"},
            {"目瞪口呆，语气充满不可思议", "SURPRISE", "Surprise-wa"},
            // ANGRY / Dissatisfaction-hnn
            {"咬牙切齿，声音低沉有力", "ANGRY", "Dissatisfaction-hnn"},
            {"怒吼咆哮，音量骤然提高", "ANGRY", "Dissatisfaction-hnn"},
            {"不耐烦地打断，语气烦躁", "ANGRY", "Dissatisfaction-hnn"},
            {"气呼呼地，声音带着怒意", "ANGRY", "Dissatisfaction-hnn"},
            // QUIET / Shh
            {"低声耳语，声音细若蚊蝇", "QUIET", "Shh"},
            {"悄声说话，生怕被人听到", "QUIET", "Shh"},
            // QUESTION / Question-ah
            {"困惑不解，语气充满疑问", "QUESTION", "Question-ah"},
            {"纳闷地挠头，声音迟疑", "QUESTION", "Question-ah"},
            // THINKING / Uhm
            {"沉吟片刻，斟酌着用词", "THINKING", "Uhm"},
            {"支支吾吾，欲言又止", "THINKING", "Uhm"},
            // 边界：情绪交叉
            {"先是惊讶，然后愤怒地大喊", "SURPRISE", "Surprise-wa"},
            {"开心地笑，但声音里带着疲惫", "HAPPY", "laughing"},
    };

    private static boolean serverAvailable;

    @BeforeAll
    static void checkServer() {
        serverAvailable = checkHealth();
        if (!serverAvailable) {
            System.out.println("[SKIP] llama.cpp server not available at " + ENDPOINT);
        }
    }

    @Test
    void measureEmotionAndVoxTagAccuracy() {
        assumeTrue(serverAvailable, "llama.cpp server not available");

        int emotionCorrect = 0;
        int voxCorrect = 0;
        int bothCorrect = 0;
        int total = TEST_CASES.length;
        StringBuilder failures = new StringBuilder();

        System.out.println("Router emotion+vox_tag accuracy test");
        System.out.println("Samples: " + total);
        System.out.println("-".repeat(70));

        for (int i = 0; i < total; i++) {
            String style = TEST_CASES[i][0];
            String expectEmotion = TEST_CASES[i][1];
            String expectVox = TEST_CASES[i][2];

            String response = callModel(style);
            String gotEmotion = extractField(response, "intent");
            String gotVox = extractField(response, "instruction");

            boolean eOk = expectEmotion.equals(gotEmotion);
            boolean vOk = expectVox.equals(gotVox);

            if (eOk) emotionCorrect++;
            if (vOk) voxCorrect++;
            if (eOk && vOk) bothCorrect++;
            else {
                failures.append(String.format("  #%02d [%s]%n", i + 1, style));
                failures.append(String.format("       expect: emotion=%s, vox=%s%n", expectEmotion, expectVox));
                failures.append(String.format("       actual: emotion=%s, vox=%s%n", gotEmotion, gotVox));
            }

            String status = (eOk && vOk) ? "OK" : "FAIL";
            System.out.printf("  #%02d %s  emotion:%s  vox:%s  [%s]%n",
                    i + 1, status, eOk ? "Y" : "N", vOk ? "Y" : "N",
                    style.length() > 25 ? style.substring(0, 25) + "..." : style);
        }

        System.out.println("-".repeat(70));
        System.out.printf("Emotion: %d/%d = %.1f%%%n", emotionCorrect, total, emotionCorrect * 100.0 / total);
        System.out.printf("VoxTag:  %d/%d = %.1f%%%n", voxCorrect, total, voxCorrect * 100.0 / total);
        System.out.printf("Both:    %d/%d = %.1f%%%n", bothCorrect, total, bothCorrect * 100.0 / total);

        if (failures.length() > 0) {
            System.out.println("\nFailures:");
            System.out.println(failures);
        }

        assertTrue(bothCorrect * 100.0 / total >= 70,
                String.format("Both-tag accuracy %.1f%% below 70%%", bothCorrect * 100.0 / total));
    }

    private static String callModel(String styleText) {
        try {
            String payload = MAPPER.writeValueAsString(new java.util.LinkedHashMap<>() {{
                put("model", "local-instruction-router");
                put("temperature", 0.0);
                put("max_tokens", 128);
                put("chat_template_kwargs", new java.util.LinkedHashMap<>() {{ put("enable_thinking", false); }});
                put("messages", new Object[]{
                        new java.util.LinkedHashMap<>() {{ put("role", "system"); put("content", TtsStyleExtractor.SCENE_INSTRUCTION); }},
                        new java.util.LinkedHashMap<>() {{ put("role", "user"); put("content", styleText); }},
                });
            }});

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return "";
            }
            JsonNode root = MAPPER.readTree(response.body());
            return root.path("choices").path(0).path("message").path("content").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private static String extractField(String json, String field) {
        if (json == null || json.isBlank()) return "";
        // Try JSON parse first
        try {
            // Extract JSON from response (handle markdown wrapping)
            String cleaned = json;
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start >= 0 && end > start) {
                cleaned = cleaned.substring(start, end + 1);
            }
            JsonNode node = MAPPER.readTree(cleaned);
            String value = node.path(field).asText("").trim();
            if (!value.isEmpty()) return value;
        } catch (Exception ignored) {
        }
        // Fallback: regex
        Pattern p = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1).trim() : "";
    }

    private static boolean checkHealth() {
        try {
            String healthUrl = ENDPOINT.replace("/v1/chat/completions", "/health");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(healthUrl))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (ConnectException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
