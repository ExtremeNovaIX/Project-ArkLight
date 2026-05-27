package p1.component.agent.tts;

import java.util.ArrayList;
import java.util.List;

/**
 * TTS 流式文本切句器。
 * <p>
 * 上游 LLM partial text 往往很碎；直接逐字合成会导致语音抖动和请求过多。
 * 该组件累计到目标长度后，等待最近的句末标点切段，避免在句中硬切。
 */
public class TtsTextChunker {

    private final TtsConfig config;
    private final StringBuilder buffer = new StringBuilder();
    private int emittedChunks = 0;

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
        if (emittedChunks == 0) {
            return firstChunkShouldFlush(length, latestChar);
        }
        return length >= config.maxChunkChars() && config.sentenceEndMarks().contains(latestChar);
    }

    private boolean firstChunkShouldFlush(int length, char latestChar) {
        if (length >= config.firstChunkMinChars() && config.sentenceEndMarks().contains(latestChar)) {
            return true;
        }
        return length >= config.firstChunkChars() && config.firstChunkEndMarks().contains(latestChar);
    }

    private int visibleLength() {
        int length = 0;
        for (int i = 0; i < buffer.length(); i++) {
            if (!Character.isWhitespace(buffer.charAt(i))) {
                length++;
            }
        }
        return length;
    }

    private void flushInto(List<String> chunks) {
        String chunk = buffer.toString().trim();
        buffer.setLength(0);
        if (!chunk.isBlank()) {
            chunks.add(chunk);
            emittedChunks++;
        }
    }
}
