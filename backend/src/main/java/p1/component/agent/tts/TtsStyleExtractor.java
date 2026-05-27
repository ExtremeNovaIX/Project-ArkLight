package p1.component.agent.tts;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import p1.component.agent.router.InstructionRouteDecision;
import p1.component.agent.router.InstructionRouteRequest;
import p1.component.agent.router.InstructionRouter;
import p1.component.agent.router.InstructionRouterTask;
import p1.component.agent.router.InstructionRouterTaskRegistry;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TTS 播音风格提取器。
 * <p>
 * 从 RP 输出的句首括号中提取播音风格描述，通过 router 小模型提取 emotion 标签（给前端切立绘）
 * 和 VoxCPM2 非语言标签（给 TTS 合成）。风格描述本身作为 VoxCPM2 的 control_instruction 透传。
 * <p>
 * 当 provider 不支持播音风格时，括号会被静默剥除，只保留正文。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TtsStyleExtractor {

    static final String TASK_ID = "tts-style-extract";

    private static final List<String> ALLOWED_EMOTIONS = List.of(
            "HAPPY", "SIGH", "SURPRISE", "ANGRY", "QUIET", "QUESTION", "THINKING", "NEUTRAL"
    );

    private static final Pattern LEADING_STYLE = Pattern.compile(
            "^\\s*[（(]([^）)]+)[）)]\\s*");

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

    static final String SCENE_INSTRUCTION = """
            <task>
            从播音风格描述中提取情绪类别和 VoxCPM2 非语言标签。
            </task>

            <allowed_emotions>
            HAPPY, SIGH, SURPRISE, ANGRY, QUIET, QUESTION, THINKING, NEUTRAL
            </allowed_emotions>

            <allowed_vox_tags>
            laughing, sigh, Uhm, Shh, Surprise-wa, Dissatisfaction-hnn, Question-ei, Question-en, Question-oh, Question-ah
            </allowed_vox_tags>

            <rules>
            - intent 字段填 emotion（大写英文）
            - instruction 字段填 vox_tag（原样输出，大小写敏感）
            - 当描述包含多种情绪时，只关注主要情绪，忽略次要或伴随状态。
            - 情绪分类优先级：看情绪词，不看音量或语速。
              悲伤/忧郁/惆怅/落寞/疲惫/哭腔 → SIGH
              压低声音/悄悄/耳语/细若蚊蝇 → QUIET
              犹豫/吞吞吐吐/支支吾吾/欲言又止/斟酌 → THINKING
              困惑/不解/迷茫/纳闷/疑问 → QUESTION
            - QUESTION 的 vox_tag 子类区分：
              Question-ah：困惑、不解、迷茫
              Question-en：沉吟、嗯、确认
            - 如果描述过于模糊、复杂或无法归入以上类别，intent 填 NEUTRAL，instruction 留空。
            </rules>

            <examples>
            <example>
            <input>语调轻快上扬，带着愉悦笑意</input>
            <output>{"intent":"HAPPY","confidence":0.95,"instruction":"laughing""}</output>
            </example>

            <example>
            <input>声音低沉缓慢，带着一丝疲惫</input>
            <output>{"intent":"SIGH","confidence":0.9,"instruction":"sigh""}</output>
            </example>

            <example>
            <input>惊讶地瞪大眼睛，声音提高</input>
            <output>{"intent":"SURPRISE","confidence":0.95,"instruction":"Surprise-wa""}</output>
            </example>

            <example>
            <input>语气严厉，语速加快，情绪激动</input>
            <output>{"intent":"ANGRY","confidence":0.9,"instruction":"Dissatisfaction-hnn""}</output>
            </example>

            <example>
            <input>轻声呢喃，压低声音悄悄说</input>
            <output>{"intent":"QUIET","confidence":0.9,"instruction":"Shh""}</output>
            </example>

            <example>
            <input>犹豫不决，吞吞吐吐地说</input>
            <output>{"intent":"THINKING","confidence":0.9,"instruction":"Uhm""}</output>
            </example>

            <example>
            <input>困惑不解，语气充满疑问</input>
            <output>{"intent":"QUESTION","confidence":0.9,"instruction":"Question-ah""}</output>
            </example>

            <example>
            <input>忧郁地呢喃，声音带着哭腔</input>
            <output>{"intent":"SIGH","confidence":0.9,"instruction":"sigh""}</output>
            </example>

            <example>
            <input>沉吟片刻，斟酌着用词</input>
            <output>{"intent":"THINKING","confidence":0.9,"instruction":"Uhm""}</output>
            </example>

            <example>
            <input>开心地笑，但声音里带着疲惫</input>
            <output>{"intent":"HAPPY","confidence":0.9,"instruction":"laughing""}</output>
            </example>
            </examples>
            """;

    private final InstructionRouter router;
    private final InstructionRouterTaskRegistry taskRegistry;

    @PostConstruct
    void registerTask() {
        taskRegistry.register(new InstructionRouterTask(TASK_ID, ALLOWED_EMOTIONS, SCENE_INSTRUCTION));
        log.info("[TTS 风格提取] 已注册路由任务: taskId={}", TASK_ID);
    }

    /**
     * 从文本中提取句首括号风格描述，调用 router 提取标签。
     * <p>
     * 返回的 {@link StyleExtractionResult} 包含清理后的正文、风格描述和标签信息。
     * 如果文本不含括号风格、router 不可用或 provider 不支持风格，返回空结果。
     *
     * @param text 原始文本片段
     * @return 提取结果
     */
    public StyleExtractionResult extract(String text) {
        if (text == null || text.isBlank()) {
            return StyleExtractionResult.empty(text);
        }

        Matcher matcher = LEADING_STYLE.matcher(text);
        if (!matcher.find()) {
            return StyleExtractionResult.empty(text);
        }

        String styleDescription = matcher.group(1).trim();
        String cleanText = text.substring(matcher.end());

        if (styleDescription.isEmpty() || cleanText.isEmpty()) {
            return StyleExtractionResult.empty(text);
        }

        // 调用 router 提取 emotion + vox_tag
        String emotion = "";
        String voxTag = "";
        try {
            InstructionRouteRequest request = InstructionRouteRequest.byTask(
                    TASK_ID, TASK_ID, "", styleDescription);
            InstructionRouteDecision decision = router.route(request);
            if (decision.available()) {
                emotion = decision.normalizedIntent();
                voxTag = decision.instruction().trim();
            }
        } catch (Exception e) {
            log.debug("[TTS 风格提取] router 调用失败，跳过标签提取: reason={}", e.getMessage());
        }

        String frontendTag = EMOTION_TO_FRONTEND_TAG.getOrDefault(emotion, "");
        String controlInstruction = buildControlInstruction(styleDescription);

        return new StyleExtractionResult(cleanText, styleDescription, frontendTag, voxTag, controlInstruction);
    }

    /**
     * 构建 VoxCPM2 control_instruction：仅风格描述。
     * <p>
     * 非语言标签 [voxTag] 不拼在 controlInstruction 里，而是由调用方插入到合成文本头部。
     */
    private static String buildControlInstruction(String styleDescription) {
        return styleDescription;
    }

    /**
     * 风格提取结果。
     *
     * @param cleanText         剥掉括号后的正文
     * @param styleDescription  括号内的原始风格描述
     * @param frontendTag       前端表情标签（如"开心"），可能为空
     * @param voxTag            VoxCPM2 非语言标签（如"laughing"），可能为空
     * @param controlInstruction VoxCPM2 control_instruction，可能为空
     */
    public record StyleExtractionResult(
            String cleanText,
            String styleDescription,
            String frontendTag,
            String voxTag,
            String controlInstruction
    ) {
        static StyleExtractionResult empty(String text) {
            return new StyleExtractionResult(
                    text == null ? "" : text, "", "", "", "");
        }

        public boolean hasStyle() {
            return !styleDescription.isEmpty();
        }
    }
}
