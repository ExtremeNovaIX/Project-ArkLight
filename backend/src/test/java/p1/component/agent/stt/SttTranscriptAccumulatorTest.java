package p1.component.agent.stt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SttTranscriptAccumulatorTest {

    @Test
    void shouldTreatPartialAsCurrentCompleteHypothesis() {
        assertEquals("等一下", SttTranscriptAccumulator.normalizePartialHypothesis(" 等一下 "));
        assertEquals("先别动", SttTranscriptAccumulator.normalizePartialHypothesis("先别动"));
    }

    @Test
    void shouldUseFinalWhenItAlreadyContainsPartial() {
        String transcript = SttTranscriptAccumulator.completeTranscript("等一下", "等一下先别动");

        assertEquals("等一下先别动", transcript);
    }

    @Test
    void shouldNotDuplicateWhenFinalRepeatsPartial() {
        String transcript = SttTranscriptAccumulator.completeTranscript("等一下", "等一下");

        assertEquals("等一下", transcript);
    }

    @Test
    void shouldAppendFinalDeltaWhenFinalOnlyContainsSuffix() {
        String transcript = SttTranscriptAccumulator.completeTranscript("我觉得先", "先防更好");

        assertEquals("我觉得先防更好", transcript);
    }

    @Test
    void shouldAppendEnglishSegmentsWithSpace() {
        String transcript = SttTranscriptAccumulator.appendSegment("hello", "world");

        assertEquals("hello world", transcript);
    }

    @Test
    void shouldAppendChineseSegmentsWithoutSpace() {
        String transcript = SttTranscriptAccumulator.appendSegment("你好", "世界");

        assertEquals("你好世界", transcript);
    }

    @Test
    void shouldAvoidDuplicatedSegments() {
        String transcript = SttTranscriptAccumulator.appendSegment("hello world", "world");

        assertEquals("hello world", transcript);
    }
}
