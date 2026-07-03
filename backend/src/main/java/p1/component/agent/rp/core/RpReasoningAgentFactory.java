package p1.component.agent.rp.core;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import p1.component.agent.factory.ChatModelFactory;
import p1.component.agent.rp.context.RpChatRequestAugmenter;
import p1.component.log.LlmServiceLoggingListenerFactory;
import p1.config.prop.AssistantProperties;

@Service
@RequiredArgsConstructor
public class RpReasoningAgentFactory {
    private final AssistantProperties assistantProperties;
    private final ChatModelFactory chatModelFactory;
    private final LlmServiceLoggingListenerFactory loggingListenerFactory;
    private final ChatMemoryProvider chatMemoryProvider;
    private final RpChatRequestAugmenter rpChatRequestAugmenter;

    public RpAgent create(RpGameReasoningEffort effort) {
        AssistantProperties.ChatModelConfig config = copy(assistantProperties.activeRpModel());
        config.setReasoningEffort((effort == null ? RpGameReasoningEffort.LOW : effort).apiValue());
        ChatModelListener listener = loggingListenerFactory.create("rp", config);
        return AiServices.builder(RpAgent.class)
                .streamingChatModel(chatModelFactory.buildStreamingChatModel(config, listener, 0.8))
                .chatMemoryProvider(chatMemoryProvider)
                .chatRequestTransformer(rpChatRequestAugmenter::augment)
                .build();
    }

    private AssistantProperties.ChatModelConfig copy(AssistantProperties.ChatModelConfig source) {
        AssistantProperties.ChatModelConfig copy = new AssistantProperties.ChatModelConfig();
        if (source == null) {
            return copy;
        }
        copy.setApiKey(source.getApiKey());
        copy.setBaseUrl(source.getBaseUrl());
        copy.setModelName(source.getModelName());
        copy.setTimeoutSeconds(source.getTimeoutSeconds());
        copy.setLogEnabled(source.isLogEnabled());
        copy.setPrompt(source.getPrompt());
        copy.setReturnThinking(source.isReturnThinking());
        copy.setSendThinking(source.isSendThinking());
        copy.setReasoningEffort(source.getReasoningEffort());
        copy.setThinkingType(source.getThinkingType());
        return copy;
    }
}