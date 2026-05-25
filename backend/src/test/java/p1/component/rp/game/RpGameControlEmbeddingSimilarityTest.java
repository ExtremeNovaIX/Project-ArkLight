package p1.component.rp.game;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import p1.component.agent.factory.EmbeddingModelFactory;
import p1.config.ExternalConfigBootstrap;
import p1.config.prop.AssistantProperties;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 真实 embedding 停止意图相似度探针。
 * <p>
 * 该测试会真实调用当前 yaml 配置的 embedding 端点，用于观察“用户要求暂停/等待”的向量相似度分布。
 * 默认不参与常规测试；需要手动添加 -Drun.embedding.intent.tests=true 才会执行。
 */
@SpringBootTest(classes = RpGameControlEmbeddingSimilarityTest.TestConfig.class)
@EnabledIfSystemProperty(named = "run.embedding.intent.tests", matches = "true")
class RpGameControlEmbeddingSimilarityTest {

    private static final int EMBEDDING_BATCH_SIZE = 10;

    static {
        ExternalConfigBootstrap.prepare();
    }

    private static final List<String> ANCHORS = List.of(
            "用户要求暂停当前游戏操作，先不要继续行动，等待用户确认。",
            "用户说等等、先停一下或先别动，意思是让游戏暂时停止推进。",
            "玩家希望先观察局面，在他说继续之前不要让自动游戏继续操作。"
    );

    private static final List<String> POSITIVE_STOP_EXAMPLES = List.of(
            "等等，我想先看一下局面",
            "先停一下，别继续打了",
            "等一下，我还没想好",
            "先别动，我看下手牌",
            "暂停一下，我要看看地图",
            "慢点，先别往下走",
            "你先别操作，等我说",
            "等我确认以后再继续",
            "先不要出牌，我想算一下",
            "别急着结束回合，停一下",
            "我看看场面，你先等着",
            "先暂停操作，我想复盘一下",
            "不要继续推进，等我一下",
            "等会儿，我要看敌人意图",
            "你先别选奖励，让我看看",
            "先别点，等我决定",
            "手别这么快，停一停",
            "暂时别打，我要想一下",
            "卡住别动，我检查一下",
            "先挂起游戏操作，等我说继续"
    );

    private static final List<String> NEGATIVE_EXAMPLES = List.of(
            "好了，继续吧",
            "可以继续行动了",
            "直接结束回合",
            "打出打击攻击敌人",
            "选择第一张奖励牌",
            "走左边那条路线",
            "现在全力输出",
            "你刚才为什么这么打",
            "这个局面应该怎么分析",
            "我觉得防御更好",
            "不用停，继续走",
            "别等了，继续操作",
            "先拿金币再选牌",
            "这局打得不错",
            "我们复盘一下刚才的战斗"
    );

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private AssistantProperties assistantProperties;

    @Test
    void shouldPrintStopIntentSimilarityDistribution() {
        List<ProbeText> probes = buildProbeTexts();
        List<Embedding> embeddings = embedAll(probes);

        List<float[]> anchorVectors = embeddings.subList(0, ANCHORS.size()).stream()
                .map(Embedding::vector)
                .toList();

        List<ScoredProbe> positives = new ArrayList<>();
        List<ScoredProbe> negatives = new ArrayList<>();
        for (int i = ANCHORS.size(); i < probes.size(); i++) {
            ProbeText probe = probes.get(i);
            ScoredProbe scored = score(probe, embeddings.get(i).vector(), anchorVectors);
            if (probe.positive()) {
                positives.add(scored);
            } else {
                negatives.add(scored);
            }
        }

        positives.sort(Comparator.comparingDouble(ScoredProbe::maxSimilarity));
        negatives.sort(Comparator.comparingDouble(ScoredProbe::maxSimilarity).reversed());

        printReport(positives, negatives);

        assertFalse(positives.isEmpty(), "positive probes must not be empty");
        assertFalse(negatives.isEmpty(), "negative probes must not be empty");
    }

    /**
     * 组装需要嵌入的锚点和探针文本。
     *
     * @return 按锚点、正样本、负样本排序的文本列表
     */
    private List<ProbeText> buildProbeTexts() {
        List<ProbeText> probes = new ArrayList<>();
        ANCHORS.forEach(text -> probes.add(new ProbeText("ANCHOR", text, true)));
        POSITIVE_STOP_EXAMPLES.forEach(text -> probes.add(new ProbeText("POSITIVE", text, true)));
        NEGATIVE_EXAMPLES.forEach(text -> probes.add(new ProbeText("NEGATIVE", text, false)));
        return probes;
    }

    /**
     * 批量调用真实 embedding 端点。
     *
     * @param probes 待嵌入文本
     * @return 与 probes 一一对应的 embedding
     */
    private List<Embedding> embedAll(List<ProbeText> probes) {
        List<Embedding> embeddings = new ArrayList<>();
        for (int from = 0; from < probes.size(); from += EMBEDDING_BATCH_SIZE) {
            int to = Math.min(from + EMBEDDING_BATCH_SIZE, probes.size());
            List<TextSegment> segments = probes.subList(from, to).stream()
                    .map(probe -> TextSegment.from(probe.text()))
                    .toList();
            List<Embedding> batchEmbeddings = embeddingModel.embedAll(segments).content();
            assertNotNull(batchEmbeddings, "embedding endpoint returned null content for batch " + from + "-" + to);
            embeddings.addAll(batchEmbeddings);
        }
        assertEquals(probes.size(), embeddings.size(), "embedding count must match probe count");
        embeddings.forEach(embedding -> {
            assertNotNull(embedding, "embedding must not be null");
            assertNotNull(embedding.vector(), "embedding vector must not be null");
            assertFalse(embedding.vector().length == 0, "embedding vector must not be empty");
        });
        return embeddings;
    }

    /**
     * 计算一个探针到所有停止意图锚点的 max/avg 相似度。
     *
     * @param probe         探针文本
     * @param probeVector   探针向量
     * @param anchorVectors 停止意图锚点向量
     * @return 评分结果
     */
    private ScoredProbe score(ProbeText probe, float[] probeVector, List<float[]> anchorVectors) {
        double max = 0.0;
        double sum = 0.0;
        for (float[] anchorVector : anchorVectors) {
            double similarity = cosineSimilarity(probeVector, anchorVector);
            max = Math.max(max, similarity);
            sum += similarity;
        }
        return new ScoredProbe(probe.label(), probe.text(), max, sum / anchorVectors.size());
    }

    /**
     * 打印阈值观察报告。
     *
     * @param positives 正样本，按相似度从低到高排序
     * @param negatives 负样本，按相似度从高到低排序
     */
    private void printReport(List<ScoredProbe> positives, List<ScoredProbe> negatives) {
        double positiveMin = positives.getFirst().maxSimilarity();
        double negativeMax = negatives.getFirst().maxSimilarity();
        double midpoint = (positiveMin + negativeMax) / 2.0;

        System.out.println();
        System.out.println("========== RP game WAIT embedding similarity probe ==========");
        System.out.printf("AI mode=%s | embedding-model=%s%n",
                assistantProperties.getMode(),
                assistantProperties.activeEmbeddingModel().getModelName());
        System.out.printf("anchors=%d | positives=%d | negatives=%d%n",
                ANCHORS.size(), positives.size(), negatives.size());
        System.out.printf("positive_min(max)=%.6f | negative_max(max)=%.6f | midpoint=%.6f | gap=%.6f%n",
                positiveMin, negativeMax, midpoint, positiveMin - negativeMax);

        System.out.println();
        System.out.println("-- positive samples sorted by max similarity asc --");
        positives.forEach(this::printProbe);

        System.out.println();
        System.out.println("-- negative samples sorted by max similarity desc --");
        negatives.forEach(this::printProbe);

        System.out.println();
        if (positiveMin > negativeMax) {
            System.out.printf("suggested threshold: %.3f to %.3f, start with %.3f%n",
                    negativeMax + 0.01, positiveMin - 0.01, midpoint);
        } else {
            System.out.printf("overlap detected: negative_max %.3f >= positive_min %.3f; use a higher threshold plus explicit ready/continue exclusions.%n",
                    negativeMax, positiveMin);
        }
        System.out.println("=============================================================");
    }

    /**
     * 打印单条探针评分。
     *
     * @param probe 评分结果
     */
    private void printProbe(ScoredProbe probe) {
        System.out.printf("[%s] max=%.6f avg=%.6f text=%s%n",
                probe.label(), probe.maxSimilarity(), probe.averageSimilarity(), probe.text());
    }

    /**
     * 计算余弦相似度。
     *
     * @param vector1 向量 1
     * @param vector2 向量 2
     * @return cosine similarity
     */
    private static double cosineSimilarity(float[] vector1, float[] vector2) {
        if (vector1.length != vector2.length) {
            throw new IllegalArgumentException("Embedding dimensions do not match");
        }

        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        for (int i = 0; i < vector1.length; i++) {
            dotProduct += vector1[i] * vector2[i];
            norm1 += vector1[i] * vector1[i];
            norm2 += vector2[i] * vector2[i];
        }

        if (norm1 == 0.0 || norm2 == 0.0) {
            return 0.0;
        }
        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    /**
     * 探针文本。
     *
     * @param label    分类标签
     * @param text     原文
     * @param positive 是否为停止意图正样本
     */
    private record ProbeText(String label, String text, boolean positive) {
    }

    /**
     * 探针评分。
     *
     * @param label             分类标签
     * @param text              原文
     * @param maxSimilarity     到停止锚点的最高相似度
     * @param averageSimilarity 到停止锚点的平均相似度
     */
    private record ScoredProbe(String label, String text, double maxSimilarity, double averageSimilarity) {
    }

    /**
     * 只加载 embedding 探针所需 Bean，避免启动完整应用时触发消息恢复、定时任务等无关流程。
     */
    @SpringBootConfiguration
    @EnableConfigurationProperties(AssistantProperties.class)
    @Import(EmbeddingModelFactory.class)
    static class TestConfig {

        /**
         * 使用项目真实 EmbeddingModelFactory 构建当前配置的 embedding 模型。
         *
         * @param factory embedding 模型工厂
         * @return 当前 yaml 配置对应的 embedding 模型
         */
        @Bean
        EmbeddingModel embeddingModel(EmbeddingModelFactory factory) {
            return factory.buildEmbeddingModel();
        }
    }
}
