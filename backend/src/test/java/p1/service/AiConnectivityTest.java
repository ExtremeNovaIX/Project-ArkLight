package p1.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import p1.config.prop.AssistantProperties;
import p1.infrastructure.vector.MemoryVectorLibrary;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@EnabledIfSystemProperty(named = "run.ai.connectivity.tests", matches = "true")
class AiConnectivityTest {

    @Autowired
    private AssistantProperties assistantProperties;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private ChatModel chatLanguageModel;

    @Test
    void embeddingService_shouldReachConfiguredEmbeddingEndpoint() {
        String probeText = "embedding connectivity probe";

        Embedding embedding = embeddingModel.embed(probeText).content();
        EmbeddingSearchResult<?> result = embeddingService.searchEmbedding("default", MemoryVectorLibrary.ARCHIVE, probeText, 1, 0.0);

        assertNotNull(embedding, "Embedding endpoint returned null content");
        assertNotNull(embedding.vector(), "Embedding vector is null");
        assertFalse(embedding.vector().length == 0, "Embedding vector is empty");
        assertNotNull(result, "EmbeddingService search result is null");
        assertNotNull(result.matches(), "EmbeddingService matches list is null");

        System.out.printf(
                "AI mode=%s | embedding-model=%s | vector-dimension=%d | match-count=%d%n",
                assistantProperties.getMode(),
                assistantProperties.activeEmbeddingModel().getModelName(),
                embedding.vector().length,
                result.matches().size()
        );
    }

    @Test
    void llm_shouldReachConfiguredChatEndpoint() {
        String reply = chatLanguageModel.chat("Reply with a short connectivity acknowledgement.");

        assertNotNull(reply, "LLM reply is null");
        assertFalse(reply.isBlank(), "LLM reply is blank");

        System.out.printf(
                "AI mode=%s | rp-model=%s | llm-reply=%s%n",
                assistantProperties.getMode(),
                assistantProperties.activeChatModel().getModelName(),
                reply
        );
    }
}
