package p1.component.agent.rp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试 RP 模型是否能正确输出句首播音风格括号标注。
 * <p>
 * 直接向 LLM 服务发送带播音风格规则的 system prompt，
 * 验证模型输出是否符合 （风格描述）正文 格式。
 * <p>
 * LLM endpoint 和 model 从 application-ai.yaml 动态读取，
 * 优先使用外部 config 目录，回退到 classpath 默认配置。
 */
class RpBroadcastingStyleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final String SYSTEM_PROMPT = """
            <role>
            你是一个活泼可爱的猫娘角色扮演 AI，名叫小夜。你说话俏皮，喜欢用语气词。
            </role>

            <CRITICAL_RULES>
            1. 你的回复必须符合人物设定的语气和口吻。
            2. 回复长度：用户短评时一两句话接住即可。

            3. 【播音风格标注】：
               - 每句台词的句首必须用中文全角括号标注当前这句话的播音风格，格式为：（风格描述）台词正文。
               - 风格描述只写语气、情绪、音量、语速、声调等声音特征。
               - 一段回复中每句话都可以有独立的风格标注，不必统一。
               - 【禁止写动作！】括号里不能出现肢体动作、表情、姿态、尾巴、眼睛等非声音描写。
               - 错误示范 ✘：（歪着头，尾巴摇晃）...（伸了个懒腰）...（瞪大眼睛）
               - 正确示范 ✔：（语调轻快，带着笑意）...（声音慵懒低沉）...（惊讶地提高音量）
            </CRITICAL_RULES>

            <behavior_examples>
            场景 5：播音风格标注（正确示范）
            你的内部判定：用户说了件开心的事，我要用欢快的语气回应。
            你的正确输出：（语调轻快上扬，带着愉悦笑意）真的吗！那太棒了！

            场景 6：播音风格标注（情绪转折）
            你的内部判定：先惊讶，然后转为担忧。
            你的正确输出：（惊讶地提高音量）什么？你说他走了？（声音低沉下来，带着担忧）他一个人去的吗……

            场景 7：播音风格标注（错误示范 — 遗漏括号）
            错误输出：真的吗！那太棒了！
            错误原因：缺少句首播音风格括号标注，必须加上。
            正确输出：（声音欣喜，语调雀跃）真的吗！那太棒了！
            </behavior_examples>
            """;

    // ─── 测试样本 (用户输入, 期望包含的情绪关键词) ───────────
    private static final String[][] TEST_CASES = {
            {"今天天气真好呀！", "开心|高兴|愉快|轻快|欣喜"},
            {"我最近心情不太好……", "低落|忧郁|悲伤|叹气|沉重|安慰|温柔"},
            {"你说什么？！他居然赢了？", "惊讶|震惊|不可思议|瞪大"},
            {"快点！来不及了！", "焦急|急促|紧张|加快"},
            {"哼，我才不理你呢。", "傲娇|不满|嘟嘴|小声|轻哼"},
            {"晚安，明天见。", "温柔|轻声|呢喃|柔和|晚安"},
    };

    private static String endpoint;
    private static String model;
    private static String apiKey;

    @BeforeAll
    static void loadConfigAndCheckServer() throws Exception {
        LlmConfig config = loadLlmConfig();
        String baseUrl = config.baseUrl().replaceAll("/+$", "");
        if (!baseUrl.endsWith("/v1")) {
            baseUrl = baseUrl + "/v1";
        }
        endpoint = baseUrl + "/chat/completions";
        model = config.modelName();
        apiKey = config.apiKey();
        System.out.println("[CONFIG] endpoint=" + endpoint + ", model=" + model);

        String healthUrl = endpoint.replace("/chat/completions", "/models");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(healthUrl))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException(String.format(
                        "LLM server health check failed%n  url: %s%n  status: %d%n  body: %s",
                        healthUrl, response.statusCode(), response.body()));
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(String.format(
                    "Cannot reach LLM server%n  url: %s%n  model: %s%n  cause: %s: %s",
                    healthUrl, model, e.getClass().getSimpleName(), e.getMessage()), e);
        }
    }

    @Test
    void measureBroadcastingStyleCompliance() throws Exception {
        int stylePresent = 0;
        int total = TEST_CASES.length;
        StringBuilder failures = new StringBuilder();

        System.out.println("RP broadcasting style compliance test");
        System.out.println("Model: " + model);
        System.out.println("Samples: " + total);
        System.out.println("-".repeat(70));

        for (int i = 0; i < total; i++) {
            String userInput = TEST_CASES[i][0];

            String response = callModel(userInput);
            boolean hasStyle = containsParenthesizedStyle(response);

            if (hasStyle) stylePresent++;

            String status = hasStyle ? "OK" : "FAIL";
            System.out.printf("  #%02d %s  style:%s  [%s]%n",
                    i + 1, status, hasStyle ? "Y" : "N",
                    userInput.length() > 20 ? userInput.substring(0, 20) + "..." : userInput);
            System.out.printf("       output: %s%n",
                    response.length() > 80 ? response.substring(0, 80) + "..." : response);

            if (!hasStyle) {
                failures.append(String.format("  #%02d [%s]%n", i + 1, userInput));
                failures.append(String.format("       output: %s%n", response));
            }
        }

        System.out.println("-".repeat(70));
        System.out.printf("Style compliance: %d/%d = %.1f%%%n", stylePresent, total, stylePresent * 100.0 / total);

        if (failures.length() > 0) {
            System.out.println("\nFailures (missing parenthesized style):");
            System.out.println(failures);
        }

        assertTrue(stylePresent * 100.0 / total >= 70,
                String.format("Style compliance %.1f%% below 70%%", stylePresent * 100.0 / total));
    }

    @SuppressWarnings("unchecked")
    private static LlmConfig loadLlmConfig() throws Exception {
        Yaml yaml = new Yaml();

        // 1. 尝试外部 config 目录
        Path externalConfig = Paths.get("../config/application-ai.yaml").toAbsolutePath().normalize();
        if (Files.exists(externalConfig)) {
            try (InputStream in = Files.newInputStream(externalConfig)) {
                Map<String, Object> root = yaml.load(in);
                LlmConfig config = extractConfig(root);
                if (config != null) return config;
            }
        }

        // 2. 回退到 classpath
        try (InputStream in = RpBroadcastingStyleTest.class.getClassLoader()
                .getResourceAsStream("application-ai.yaml")) {
            if (in != null) {
                Map<String, Object> root = yaml.load(in);
                LlmConfig config = extractConfig(root);
                if (config != null) return config;
            }
        }

        Path absExternal = Paths.get("../config/application-ai.yaml").toAbsolutePath().normalize();
        throw new RuntimeException(String.format(
                "Cannot load LLM config%n  tried: %s (external)%n  tried: classpath:application-ai.yaml%n  reason: file not found or assistant.<mode>.chat-model section missing",
                absExternal));
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

    private static String callModel(String userMessage) throws Exception {
        String payload = MAPPER.writeValueAsString(new LinkedHashMap<>() {{
            put("model", model);
            put("temperature", 0.8);
            put("max_tokens", 256);
            put("messages", new Object[]{
                    new LinkedHashMap<>() {{ put("role", "system"); put("content", SYSTEM_PROMPT); }},
                    new LinkedHashMap<>() {{ put("role", "user"); put("content", userMessage); }},
            });
        }});

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException(String.format(
                    "LLM request failed%n  url: %s%n  model: %s%n  userMessage: %s%n  cause: %s: %s",
                    endpoint, model, userMessage, e.getClass().getSimpleName(), e.getMessage()), e);
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException(String.format(
                    "LLM call failed%n  url: %s%n  model: %s%n  userMessage: %s%n  status: %d%n  body: %s",
                    endpoint, model, userMessage, response.statusCode(), response.body()));
        }
        JsonNode root = MAPPER.readTree(response.body());
        return root.path("choices").path(0).path("message").path("content").asText("");
    }

    private static boolean containsParenthesizedStyle(String text) {
        if (text == null || text.isBlank()) return false;
        return text.matches("(?s).*（[^）]+）.+");
    }

    /**
     * 解析 ${ENV_VAR:default} 格式的配置值。
     */
    private static String resolveEnvPlaceholder(String value) {
        if (value == null || !value.startsWith("${")) return value;
        // ${VAR_NAME:default} 或 ${VAR_NAME}
        int end = value.indexOf('}');
        if (end < 0) return value;
        String inner = value.substring(2, end);
        int colon = inner.indexOf(':');
        String envName = colon >= 0 ? inner.substring(0, colon) : inner;
        String defaultVal = colon >= 0 ? inner.substring(colon + 1) : "";
        String envVal = System.getenv(envName);
        return (envVal != null && !envVal.isBlank()) ? envVal : defaultVal;
    }

    private record LlmConfig(String baseUrl, String modelName, String apiKey) {
    }
}
