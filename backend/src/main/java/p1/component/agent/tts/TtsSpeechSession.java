package p1.component.agent.tts;

/**
 * 一次 RP 发言对应的 TTS 会话。
 * <p>
 * 上游可以持续写入模型 partial text；会话内部负责切句、排队合成和结束通知。
 */
public interface TtsSpeechSession extends AutoCloseable {

    /**
     * 写入一段新的可见文本。
     *
     * @param text 模型流式输出片段
     */
    void accept(String text);

    /**
     * 正常结束本次发言，刷新剩余文本并发送 final 事件。
     */
    void finish();

    /**
     * 取消本次发言，丢弃尚未合成的文本。
     *
     * @param reason 取消原因
     */
    void cancel(String reason);

    @Override
    default void close() {
        finish();
    }
}
