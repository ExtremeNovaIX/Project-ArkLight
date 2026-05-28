package p1.component.agent.tts;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TtsTextChunkerTest {

    @Test
    void shouldUseShortFirstChunkThenDefaultChunks() {
        TtsConfig config = new TtsConfig();
        config.setFirstChunkChars(5);
        config.setMaxChunkChars(10);
        TtsTextChunker chunker = new TtsTextChunker(config);

        List<String> chunks = chunker.append("abcde.1234567890.tail");

        assertEquals(List.of("abcde.", "1234567890."), chunks);
        assertEquals(List.of("tail"), chunker.flush());
    }

    @Test
    void shouldWaitForNearestSentenceEndAfterTargetLength() {
        TtsConfig config = new TtsConfig();
        config.setFirstChunkChars(5);
        TtsTextChunker chunker = new TtsTextChunker(config);

        List<String> chunks = chunker.append("abcdef");

        assertEquals(List.of(), chunks);
        assertEquals(List.of("abcdef"), chunker.flush());
    }

    @Test
    void shouldIgnoreWhitespaceWhenCountingChunkLength() {
        TtsConfig config = new TtsConfig();
        config.setFirstChunkChars(5);
        TtsTextChunker chunker = new TtsTextChunker(config);

        List<String> chunks = chunker.append("a b c d e.");

        assertEquals(List.of("a b c d e."), chunks);
    }

    @Test
    void shouldFlushShortFirstSentenceAfterMinimumLength() {
        TtsConfig config = new TtsConfig();
        config.setFirstChunkMinChars(3);
        config.setFirstChunkChars(10);
        TtsTextChunker chunker = new TtsTextChunker(config);

        List<String> chunks = chunker.append("abc.tail");

        assertEquals(List.of("abc."), chunks);
        assertEquals(List.of("tail"), chunker.flush());
    }

    @Test
    void shouldAllowCommaAsFirstChunkBoundaryAfterTargetLength() {
        TtsConfig config = new TtsConfig();
        config.setFirstChunkMinChars(10);
        config.setFirstChunkChars(5);
        TtsTextChunker chunker = new TtsTextChunker(config);

        List<String> chunks = chunker.append("abcde,tail.");

        assertEquals(List.of("abcde,"), chunks);
        assertEquals(List.of("tail."), chunker.flush());
    }
}
