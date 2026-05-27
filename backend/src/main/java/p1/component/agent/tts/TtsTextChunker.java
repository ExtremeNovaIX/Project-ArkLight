package p1.component.agent.tts;

import java.util.ArrayList;
import java.util.List;

/**
 * TTS 流式文本切句器。
 * <p>
 * 上游 LLM partial text 往往很碎；直接逐字合成会导致语音抖动和请求过多。
 * 该组件按标点和最大长度切段，在低延迟与自然度之间取一个保守平衡。
 */
public class TtsTextChunker {

    private final TtsConfig config;
    private final StringBuilder buffer = new StringBuilder();

    public TtsTextChunker(TtsConfig config) {
        this.config = config;
    }

    /**
     * 追加文本并返回已经可以合成的完整片段。
     *
     * @param text 新文本
     * @return 可合成片段
     */
    public synchronized List<String> append(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        for (char c : text.toCharArray()) {
            buffer.append(c);
            if (shouldFlush(c)) {
                flushInto(chunks);
            }
        }
        return chunks;
    }

    /**
     * 刷新剩余文本。
     *
     * @return 剩余片段
     */
    public synchronized List<String> flush() {
        List<String> chunks = new ArrayList<>();
        flushInto(chunks);
        return chunks;
    }

    private boolean shouldFlush(char latestChar) {
        int length = visibleLength();
        if (length >= Math.max(1, config.maxChunkChars())) {
            return true;
        }
        return length >= Math.max(1, config.minChunkChars())
                && config.sentenceEndMarks().contains(latestChar);
    }

    private int visibleLength() {
        return buffer.toString().trim().length();
    }

    private void flushInto(List<String> chunks) {
        String chunk = buffer.toString().trim();
        buffer.setLength(0);
        if (!chunk.isBlank()) {
            chunks.add(chunk);
        }
    }
}
