package p1.config;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import p1.benchmark.halumem.HaluMemMemoryJudgeAiService;
import p1.benchmark.halumem.HaluMemQaAnswerAiService;
import p1.benchmark.halumem.HaluMemQaJudgeAiService;
import p1.component.agent.gamer.GamerStreamingAgent;
import p1.component.agent.gamer.memory.GamerMemoryCompressorAiService;
import p1.component.agent.gamer.memory.TurnScopedGamerChatMemory;
import p1.component.agent.memory.FactEvaluatorAiService;
import p1.component.agent.memory.FactExtractionAiService;
import p1.component.agent.rp.CallSolverTool;
import p1.component.agent.rp.context.RpChatRequestAugmenter;
import p1.component.agent.rp.core.RpAgent;
import p1.component.agent.rp.game.tool.RpGameToolProvider;
import p1.component.agent.rp.proactive.RpProactiveAgent;
import p1.component.agent.task.checker.TaskCheckerAiService;

/**
 * LangChain4j AiService 组装配置。
 * <p>
 * 该类只描述服务与模型、记忆、工具之间的绑定关系。
 */
@Configuration
public class AiServiceConfig {

    /**
     * 构建常规 RP 对话服务。
     *
     * @param streamingChatModel     RP 流式模型
     * @param chatMemoryProvider     RP 记忆 provider
     * @param rpChatRequestAugmenter RP 请求增强器
     * @param rpGameToolProvider     游戏模式动态工具层
     * @param callSolverTool         解题工具
     * @return RP 对话服务
     */
    @Bean
    public RpAgent rpAgent(@Qualifier("rpStreamingChatModel") StreamingChatModel streamingChatModel,
                           ChatMemoryProvider chatMemoryProvider,
                           RpChatRequestAugmenter rpChatRequestAugmenter,
                           RpGameToolProvider rpGameToolProvider,
                           CallSolverTool callSolverTool) {
        return AiServices.builder(RpAgent.class)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .chatRequestTransformer(rpChatRequestAugmenter::augment)
                .toolProvider(rpGameToolProvider)
                .build();
    }

    /**
     * 构建 RP 主动发言生成器。
     *
     * @param streamingChatModel RP 流式模型
     * @return 主动发言服务
     */
    @Bean
    public RpProactiveAgent rpProactiveAgent(
            @Qualifier("rpStreamingChatModel") StreamingChatModel streamingChatModel) {
        return AiServices.builder(RpProactiveAgent.class)
                .streamingChatModel(streamingChatModel)
                .build();
    }

    /**
     * 构建任务检查服务。
     *
     * @param supervisorChatModel 监督模型
     * @return 任务检查服务
     */
    @Bean
    public TaskCheckerAiService taskSupervisorCheckerAiService(
            @Qualifier("supervisorChatModel") ChatModel supervisorChatModel) {
        return AiServices.builder(TaskCheckerAiService.class)
                .chatModel(supervisorChatModel)
                .build();
    }

    /**
     * 构建事实抽取服务。
     *
     * @param backendChatModel 后台模型
     * @return 事实抽取服务
     */
    @Bean
    public FactExtractionAiService factExtractionAiService(@Qualifier("backendChatModel") ChatModel backendChatModel) {
        return AiServices.builder(FactExtractionAiService.class)
                .chatModel(backendChatModel)
                .build();
    }

    /**
     * 构建事实评分服务。
     *
     * @param backendChatModel 后台模型
     * @return 事实评分服务
     */
    @Bean
    public FactEvaluatorAiService factScoringAiService(@Qualifier("backendChatModel") ChatModel backendChatModel) {
        return AiServices.builder(FactEvaluatorAiService.class)
                .chatModel(backendChatModel)
                .build();
    }

    /**
     * 构建 gamer 工作记忆压缩服务。
     *
     * @param backendChatModel 后台模型
     * @return gamer 记忆压缩服务
     */
    @Bean
    public GamerMemoryCompressorAiService gamerMemoryCompressorAiService(
            @Qualifier("backendChatModel") ChatModel backendChatModel) {
        return AiServices.builder(GamerMemoryCompressorAiService.class)
                .chatModel(backendChatModel)
                .build();
    }

    /**
     * 构建 gamer 流式服务。
     *
     * @param streamingChatModel gamer 流式模型
     * @return gamer 流式服务
     */
    @Bean
    public GamerStreamingAgent gamerStreamingAgent(
            @Qualifier("gamerStreamingChatModel") StreamingChatModel streamingChatModel) {
        return AiServices.builder(GamerStreamingAgent.class)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(TurnScopedGamerChatMemory::new)
                .build();
    }

    /**
     * 构建 HaluMem QA 回答服务。
     *
     * @param supervisorChatModel 监督模型
     * @return QA 回答服务
     */
    @Bean
    public HaluMemQaAnswerAiService haluMemQaAnswerAiService(
            @Qualifier("supervisorChatModel") ChatModel supervisorChatModel) {
        return AiServices.builder(HaluMemQaAnswerAiService.class)
                .chatModel(supervisorChatModel)
                .build();
    }

    /**
     * 构建 HaluMem 记忆裁判服务。
     *
     * @param supervisorChatModel 监督模型
     * @return 记忆裁判服务
     */
    @Bean
    public HaluMemMemoryJudgeAiService haluMemMemoryJudgeAiService(
            @Qualifier("supervisorChatModel") ChatModel supervisorChatModel) {
        return AiServices.builder(HaluMemMemoryJudgeAiService.class)
                .chatModel(supervisorChatModel)
                .build();
    }

    /**
     * 构建 HaluMem QA 裁判服务。
     *
     * @param supervisorChatModel 监督模型
     * @return QA 裁判服务
     */
    @Bean
    public HaluMemQaJudgeAiService haluMemQaJudgeAiService(
            @Qualifier("supervisorChatModel") ChatModel supervisorChatModel) {
        return AiServices.builder(HaluMemQaJudgeAiService.class)
                .chatModel(supervisorChatModel)
                .build();
    }
}
