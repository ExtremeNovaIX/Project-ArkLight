package p1.component.agent.tts;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TtsTextNormalizerTest {

    @Test
    void shouldRemoveEmotionTagsAndMarkdownNoise() {
        TtsTextNormalizer normalizer = new TtsTextNormalizer();

        String normalized = normalizer.normalize("[开心] **你好**，[地图](http://example.com) 已经打开。");

        assertEquals("你好，地图 已经打开。", normalized);
    }

    @Test
    void shouldRemoveGenericBracketTagsBeforeTts() {
        TtsTextNormalizer normalizer = new TtsTextNormalizer();

        String normalized = normalizer.normalize("[正常][*]【生气】这句给前端看标签，但 TTS 不读标签。");

        assertEquals("这句给前端看标签，但 TTS 不读标签。", normalized);
    }

    @Test
    void shouldRemoveTagsAfterStreamingFragmentsAreJoined() {
        TtsConfig config = new TtsConfig();
        TtsTextNormalizer normalizer = new TtsTextNormalizer();
        TtsTextChunker chunker = new TtsTextChunker(config);
        List<String> chunks = new ArrayList<>();

        chunks.addAll(chunker.append(normalizer.normalize("[开")));
        chunks.addAll(chunker.append(normalizer.normalize("心]你好。")));
        chunks.addAll(chunker.flush());
        String joinedChunk = chunks.get(0);

        assertEquals("你好。", normalizer.normalize(joinedChunk));
    }
}
