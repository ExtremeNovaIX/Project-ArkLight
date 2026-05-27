package p1.component.agent.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import javax.sound.sampled.*;
import java.io.*;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 全链路集成测试：RP LLM → 风格解析 → Router 提取标签 → VoxCPM2 合成 → 播放。
 * <p>
 * LLM 不可用时测试直接失败（与 {@code RpBroadcastingStyleTest} 一致），
 * Router 和 VoxCPM2 不可用时对应阶段跳过。
 */
class RpTtsPipelineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final String RP_SYSTEM_PROMPT = """
            <role>
            你是一个活泼可爱的猫娘角色扮演 AI，名叫小夜。你说话俏皮，喜欢用语气词。
            </role>

            <CRITICAL_RULES>
            1. 你的回复必须符合人物设定的语气和口吻。
            2. 回复长度：用户短评时一两句话接住即可。

            3. 【播音风格标注 — 极度重要】：
               - 每句台词的句首必须用中文全角括号标注当前这句话的播音风格，格式为：（风格描述）台词正文。
               - 风格描述只写声音特征：语气、情绪、音量、语速、声调、气息。
               - 一段回复中每句话都可以有独立的风格标注，不必统一。
               - ══ 绝对禁止写动作 ══
                 括号里不能出现任何肢体动作、面部表情、姿态、动物行为。
                 包括但不限于：竖起耳朵、歪着头、摇尾巴、瞪大眼睛、伸懒腰、舔爪子、炸毛、蹭、扑、跳、转圈、打滚。
               - ══ 正面案例（全部正确）══
                 ✔（声线上扬，透着好奇）
                 ✔（迟疑地停顿了一下，声音放轻）
                 ✔（语调不安，微微发颤）
                 ✔（惊讶地拔高了音调）
                 ✔（亲昵地软下嗓子）
                 ✔（声音慵懒拖长，带着倦意）
            </CRITICAL_RULES>

            <behavior_examples>
            场景 1：普通闲聊（无需工具）
            用户输入："你觉得今天的月亮好看吗？"
            你的正确输出：（语调轻快俏皮，带着慵懒笑意）嗯？月亮吗？我觉得还没有我的尾巴好看呢！

            场景 2：播音风格标注（正确示范）
            你的正确输出：（语调轻快上扬，带着愉悦笑意）真的吗！那太棒了！

            场景 3：播音风格标注（情绪转折）
            你的正确输出：（惊讶地拔高音调）什么？你说他走了？（声音沉下来，气息变轻）他一个人去的吗……
            </behavior_examples>
            """;

    private static final Map<String, String> EMOTION_TO_FRONTEND_TAG = Map.ofEntries(
            Map.entry("HAPPY", "开心"),
            Map.entry("SIGH", "叹气"),
            Map.entry("SURPRISE", "惊讶"),
            Map.entry("ANGRY", "生气"),
            Map.entry("QUIET", "小声"),
            Map.entry("QUESTION", "疑惑"),
            Map.entry("THINKING", "思考"),
            Map.entry("NEUTRAL", "")
    );

    // ─── Service config ───────────────────────────────────────────
    private static String llmEndpoint;
    private static String llmModel;
    private static String llmApiKey;

    private static String routerEndpoint;
    private static boolean routerAvailable;

    private static String ttsEndpoint;
    private static boolean ttsAvailable;

    @BeforeAll
    static void setUp() throws Exception {
        // ── LLM config ──
        LlmConfig llm = loadLlmConfig();
        String baseUrl = llm.baseUrl().replaceAll("/+$", "");
        if (!baseUrl.endsWith("/v1")) {
            baseUrl = baseUrl + "/v1";
        }
        llmEndpoint = baseUrl + "/chat/completions";
        llmModel = llm.modelName();
        llmApiKey = llm.apiKey();

        // Health check
        String healthUrl = baseUrl + "/models";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(healthUrl))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + llmApiKey)
                .GET()
                .build();
        try {
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException(String.format(
                        "LLM health check failed%n  url: %s%n  status: %d%n  body: %s",
                        healthUrl, resp.statusCode(), resp.body()));
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(String.format(
                    "Cannot reach LLM server%n  url: %s%n  model: %s%n  cause: %s: %s",
                    healthUrl, llmModel, e.getClass().getSimpleName(), e.getMessage()), e);
        }
        System.out.println("[CONFIG] LLM endpoint=" + llmEndpoint + ", model=" + llmModel);

        // ── Router ──
        routerEndpoint = "http://127.0.0.1:8087/v1/chat/completions";
        routerAvailable = checkHealth("http://127.0.0.1:8087/health");
        System.out.println("[CONFIG] Router available=" + routerAvailable);

        // ── TTS ──
        ttsEndpoint = "http://127.0.0.1:8810/tts";
        ttsAvailable = checkHealth("http://127.0.0.1:8810/");
        if (!ttsAvailable) {
            // server might not have GET / but still serve POST /tts
            try {
                HttpRequest r = HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:8810/tts"))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build();
                HTTP.send(r, HttpResponse.BodyHandlers.discarding());
                ttsAvailable = true;
            } catch (ConnectException e) {
                // definitely not available
            } catch (Exception e) {
                ttsAvailable = true; // server responded, just no GET handler
            }
        }
        System.out.println("[CONFIG] TTS available=" + ttsAvailable);
    }

    @Test
    void fullPipeline() throws Exception {
        String userMessage = "今天天气真好呀！快出来玩！对了，你听说了吗？小明居然考试得了第一名。";

        System.out.println("\n" + "=".repeat(70));
        System.out.println("FULL PIPELINE: RP LLM → Parse → Router → TTS → Playback");
        System.out.println("=".repeat(70));
        System.out.println("User: " + userMessage);
        System.out.println();

        // ─── Stage 1: RP LLM (streaming) ──────────────────────
        System.out.println("--- Stage 1: RP LLM Streaming ---");
        List<String> rawChunks = new ArrayList<>();
        String fullResponse = streamRpResponse(userMessage, rawChunks);
        System.out.println("  Raw chunks: " + rawChunks.size());
        System.out.println("  Full response:");
        System.out.println("    " + fullResponse);
        assertFalse(fullResponse.isBlank(), "RP LLM returned empty response");
        System.out.println();

        // ─── Stage 2: Parse sentences ─────────────────────────
        System.out.println("--- Stage 2: Parse Style + Text ---");
        List<SentencePair> sentences = parseSentences(fullResponse);
        System.out.println("  Parsed " + sentences.size() + " sentence(s):");
        for (int i = 0; i < sentences.size(); i++) {
            SentencePair s = sentences.get(i);
            System.out.printf("  #%d style=\"%s\"  text=\"%s\"%n", i + 1, s.style, s.text);
        }
        assertFalse(sentences.isEmpty(), "No sentences parsed from RP output");
        System.out.println();

        // ─── Stage 3: Router (emotion + vox_tag) ──────────────
        System.out.println("--- Stage 3: Router Tag Extraction ---");
        List<RouterResult> routerResults = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) {
            SentencePair s = sentences.get(i);
            RouterResult r;
            if (routerAvailable) {
                r = callRouter(s.style);
                String frontendTag = EMOTION_TO_FRONTEND_TAG.getOrDefault(r.emotion, "");
                System.out.printf("  #%d style=\"%s\"%n", i + 1, s.style);
                System.out.printf("     → emotion=%s, vox_tag=%s, frontendTag=%s%n", r.emotion, r.voxTag, frontendTag);
                System.out.printf("     → controlInstruction=\"%s\"%n", s.style);
                if (!r.voxTag.isEmpty()) {
                    System.out.printf("     → ttsText=\"[%s]%s\"%n", r.voxTag, s.text);
                }
            } else {
                r = RouterResult.empty();
                System.out.printf("  #%d [SKIP] Router unavailable%n", i + 1);
            }
            routerResults.add(r);
        }
        System.out.println();

        // ─── Stage 4: TTS Synthesis ───────────────────────────
        System.out.println("--- Stage 4: TTS Synthesis ---");
        List<AudioChunk> audioChunks = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) {
            SentencePair s = sentences.get(i);
            RouterResult r = routerResults.get(i);
            String ttsText = r.voxTag.isEmpty() ? s.text : "[" + r.voxTag + "]" + s.text;

            if (ttsAvailable) {
                byte[] audio = callTts(ttsText, s.style);
                Path wavPath = Paths.get(System.getProperty("java.io.tmpdir"),
                        "pipeline_" + String.format("%03d", i + 1) + ".wav");
                Files.write(wavPath, audio);
                audioChunks.add(new AudioChunk(wavPath, audio.length, s.text));
                System.out.printf("  #%d ttsText=\"%s\" ctrl=\"%s\"%n", i + 1, ttsText, s.style);
                System.out.printf("     → %d bytes → %s%n", audio.length, wavPath);
            } else {
                System.out.printf("  #%d [SKIP] TTS unavailable%n", i + 1);
            }
        }
        System.out.println();

        // ─── Stage 5: Audio Playback ──────────────────────────
        if (!audioChunks.isEmpty()) {
            System.out.println("--- Stage 5: Audio Playback ---");
            for (int i = 0; i < audioChunks.size(); i++) {
                AudioChunk chunk = audioChunks.get(i);
                System.out.printf("  Playing #%d: %s (%d bytes)%n", i + 1, chunk.path.getFileName(), chunk.size);
                playWav(chunk.path);
            }
            System.out.println("  Done.");
        }

        System.out.println("\n" + "=".repeat(70));
        System.out.println("PIPELINE COMPLETE");
        System.out.printf("  Sentences: %d, Audio: %d%n", sentences.size(), audioChunks.size());
        System.out.println("=".repeat(70));
    }

    // ─── RP LLM streaming ────────────────────────────────────────

    private String streamRpResponse(String userMessage, List<String> rawChunks) throws Exception {
        String payload = MAPPER.writeValueAsString(new LinkedHashMap<>() {{
            put("model", llmModel);
            put("temperature", 0.8);
            put("max_tokens", 512);
            put("stream", true);
            put("messages", new Object[]{
                    new LinkedHashMap<>() {{ put("role", "system"); put("content", RP_SYSTEM_PROMPT); }},
                    new LinkedHashMap<>() {{ put("role", "user"); put("content", userMessage); }},
            });
        }});

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(llmEndpoint))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + llmApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new RuntimeException("RP LLM returned " + response.statusCode() + ": " + body);
        }

        StringBuilder full = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) break;
                JsonNode node = MAPPER.readTree(data);
                String delta = node.path("choices").path(0).path("delta").path("content").asText("");
                if (!delta.isEmpty()) {
                    full.append(delta);
                    rawChunks.add(delta);
                }
            }
        }
        return full.toString();
    }

    // ─── Sentence parser ─────────────────────────────────────────

    record SentencePair(String style, String text) {
    }

    /**
     * 从 RP 输出中解析（风格）正文 句对，支持多句交替。
     */
    static List<SentencePair> parseSentences(String raw) {
        List<SentencePair> result = new ArrayList<>();
        int i = 0;
        while (i < raw.length()) {
            while (i < raw.length() && Character.isWhitespace(raw.charAt(i))) i++;
            if (i >= raw.length()) break;

            char c = raw.charAt(i);
            if (c != '(' && c != '（') {
                int nextParen = findNextParen(raw, i + 1);
                if (nextParen < 0) break;
                i = nextParen;
                continue;
            }

            char closeParen = c == '(' ? ')' : '）';
            i++;

            int styleStart = i;
            while (i < raw.length() && raw.charAt(i) != closeParen) i++;
            if (i >= raw.length()) break;
            String style = raw.substring(styleStart, i).trim();
            i++;

            int textStart = i;
            while (i < raw.length()) {
                char ch = raw.charAt(i);
                if (ch == '(' || ch == '（') {
                    int lookback = i - 1;
                    while (lookback >= textStart && Character.isWhitespace(raw.charAt(lookback))) lookback--;
                    if (lookback >= textStart && isSentenceEnd(raw.charAt(lookback))) {
                        break;
                    }
                }
                i++;
            }
            String text = raw.substring(textStart, i).trim();

            if (!style.isEmpty() && !text.isEmpty()) {
                result.add(new SentencePair(style, text));
            }
        }
        return result;
    }

    private static int findNextParen(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '（') return i;
        }
        return -1;
    }

    private static boolean isSentenceEnd(char c) {
        return c == '！' || c == '。' || c == '？' || c == '…' || c == '~'
                || c == '!' || c == '?' || c == '.';
    }

    // ─── Router ──────────────────────────────────────────────────

    record RouterResult(String emotion, String voxTag, String raw) {
        static RouterResult empty() {
            return new RouterResult("", "", "");
        }
    }

    private RouterResult callRouter(String styleDescription) {
        try {
            String payload = MAPPER.writeValueAsString(new LinkedHashMap<>() {{
                put("model", "local-instruction-router");
                put("temperature", 0.0);
                put("max_tokens", 128);
                put("chat_template_kwargs", new LinkedHashMap<>() {{ put("enable_thinking", false); }});
                put("messages", new Object[]{
                        new LinkedHashMap<>() {{ put("role", "system"); put("content", TtsStyleExtractor.SCENE_INSTRUCTION); }},
                        new LinkedHashMap<>() {{ put("role", "user"); put("content", styleDescription); }},
                });
            }});

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(routerEndpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.out.printf("     [WARN] Router returned %d%n", response.statusCode());
                return RouterResult.empty();
            }

            JsonNode root = MAPPER.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            return new RouterResult(
                    extractJsonField(content, "intent"),
                    extractJsonField(content, "instruction"),
                    content);
        } catch (Exception e) {
            System.out.printf("     [WARN] Router error: %s%n", e.getMessage());
            return RouterResult.empty();
        }
    }

    private static String extractJsonField(String json, String field) {
        if (json == null || json.isBlank()) return "";
        try {
            String cleaned = json;
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start >= 0 && end > start) cleaned = cleaned.substring(start, end + 1);
            return MAPPER.readTree(cleaned).path(field).asText("").trim();
        } catch (Exception e) {
            return "";
        }
    }

    // ─── TTS ─────────────────────────────────────────────────────

    private byte[] callTts(String text, String controlInstruction) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        body.put("control_instruction", controlInstruction);
        body.put("media_type", "wav");
        body.put("normalize", true);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ttsEndpoint))
                .timeout(Duration.ofSeconds(300))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();

        HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            String errBody = new String(response.body(), StandardCharsets.UTF_8);
            throw new RuntimeException("TTS returned " + response.statusCode() + ": " + errBody);
        }
        return response.body();
    }

    // ─── Audio playback ──────────────────────────────────────────

    record AudioChunk(Path path, int size, String text) {
    }

    private void playWav(Path wavPath) throws Exception {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(wavPath.toFile())) {
            Clip clip = AudioSystem.getClip();
            clip.open(ais);
            clip.start();
            while (!clip.isRunning()) Thread.sleep(10);
            while (clip.isRunning()) Thread.sleep(50);
            clip.close();
        }
    }

    // ─── Config loading ──────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static LlmConfig loadLlmConfig() throws Exception {
        Yaml yaml = new Yaml();

        Path externalConfig = Paths.get("../config/application-ai.yaml").toAbsolutePath().normalize();
        if (Files.exists(externalConfig)) {
            try (InputStream in = Files.newInputStream(externalConfig)) {
                Map<String, Object> root = yaml.load(in);
                LlmConfig config = extractConfig(root);
                if (config != null) return config;
            }
        }

        try (InputStream in = RpTtsPipelineTest.class.getClassLoader()
                .getResourceAsStream("application-ai.yaml")) {
            if (in != null) {
                Map<String, Object> root = yaml.load(in);
                LlmConfig config = extractConfig(root);
                if (config != null) return config;
            }
        }

        throw new RuntimeException("Cannot load LLM config from application-ai.yaml");
    }

    @SuppressWarnings("unchecked")
    private static LlmConfig extractConfig(Map<String, Object> root) {
        Map<String, Object> assistant = (Map<String, Object>) root.get("assistant");
        if (assistant == null) return null;

        String mode = String.valueOf(assistant.getOrDefault("mode", "api"));
        Map<String, Object> modeConfig = (Map<String, Object>) assistant.get(mode);
        if (modeConfig == null) return null;

        Map<String, Object> chatModel = (Map<String, Object>) modeConfig.get("chat-model");
        if (chatModel == null) return null;

        String baseUrl = String.valueOf(chatModel.getOrDefault("base-url", ""));
        String modelName = String.valueOf(chatModel.getOrDefault("model-name", ""));
        String rawApiKey = String.valueOf(chatModel.getOrDefault("api-key", ""));
        if (baseUrl.isBlank() || modelName.isBlank()) return null;

        return new LlmConfig(baseUrl, modelName, resolveEnvPlaceholder(rawApiKey));
    }

    private static String resolveEnvPlaceholder(String value) {
        if (value == null || !value.startsWith("${")) return value;
        int end = value.indexOf('}');
        if (end < 0) return value;
        String inner = value.substring(2, end);
        int colon = inner.indexOf(':');
        String envName = colon >= 0 ? inner.substring(0, colon) : inner;
        String defaultVal = colon >= 0 ? inner.substring(colon + 1) : "";
        String envVal = System.getenv(envName);
        return (envVal != null && !envVal.isBlank()) ? envVal : defaultVal;
    }

    private static boolean checkHealth(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<Void> resp = HTTP.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private record LlmConfig(String baseUrl, String modelName, String apiKey) {
    }
}
