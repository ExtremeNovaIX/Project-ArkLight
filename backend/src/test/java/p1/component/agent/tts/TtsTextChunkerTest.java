package p1.component.agent.tts;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TtsTextChunkerTest {

    @Test
    void shouldFlushWhenSentenceEndMarkArrivesAfterMinLength() {
        TtsTextChunker chunker = new TtsTextChunker(new TtsConfig());

        List<String> chunks = chunker.append("你好呀，我准备好了。下一句");

        assertEquals(List.of("你好呀，我准备好了。"), chunks);
        assertEquals(List.of("下一句"), chunker.flush());
    }

    @Test
    void shouldForceFlushWhenChunkIsTooLong() {
        TtsConfig config = new TtsConfig() {
            @Override
            public int maxChunkChars() {
                return 4;
            }
        };
        TtsTextChunker chunker = new TtsTextChunker(config);

        List<String> chunks = chunker.append("一二三四五");

        assertEquals(List.of("一二三四"), chunks);
        assertEquals(List.of("五"), chunker.flush());
    }
}
