package p1.component.agent.context;

import org.junit.jupiter.api.Test;
import p1.component.agent.rp.context.SummaryCacheManager;
import p1.config.prop.AssistantProperties;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SummaryCacheManagerTest {

    @Test
    void shouldAppendBucketedTimeAfterSummaryContent() {
        AssistantProperties props = new AssistantProperties();
        AssistantProperties.ChatMemoryConfig chatMemoryConfig = new AssistantProperties.ChatMemoryConfig();
        chatMemoryConfig.setContextMaxSummaryCount(4);
        props.setChatMemory(chatMemoryConfig);

        SummaryCacheManager manager = new SummaryCacheManager(props);
        manager.updateSummary("s1", "第一条摘要", LocalDateTime.of(2026, 4, 29, 10, 44, 0));

        String summary = manager.getSummary("s1");
        assertTrue(summary.contains("摘要 1: 第一条摘要\n时间：10:30"));
        assertTrue(summary.indexOf("第一条摘要") < summary.indexOf("时间：10:30"));
    }
}
