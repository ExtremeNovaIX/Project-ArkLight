package p1.component.agent.factory;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import p1.config.prop.AssistantProperties;
import p1.utils.HttpUtil;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class EmbeddingModelFactory {

    private final AssistantProperties props;

    public EmbeddingModel buildEmbeddingModel() {
        AssistantProperties.EmbeddingModelConfig embeddingModelConfig = props.activeEmbeddingModel();
        OpenAiEmbeddingModel.OpenAiEmbeddingModelBuilder builder = OpenAiEmbeddingModel.builder()
                .baseUrl(embeddingModelConfig.getBaseUrl())
                .modelName(embeddingModelConfig.getModelName());

        if (StringUtils.hasText(embeddingModelConfig.getApiKey())) {
            builder.apiKey(embeddingModelConfig.getApiKey());
        }

        if (HttpUtil.isLocalEndpoint(embeddingModelConfig.getBaseUrl())) {
            builder.httpClientBuilder(HttpUtil.getLocalClientBuilder(Duration.ofSeconds(30)));
        }

        return builder.build();
    }
}
