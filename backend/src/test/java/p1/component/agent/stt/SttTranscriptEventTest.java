package p1.component.agent.stt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SttTranscriptEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldRejectPayloadWithoutAsrV2Type() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> SttTranscriptEvent.fromJson(
                objectMapper.readTree("{\"text\":\"hello\",\"final\":false}")));
    }

    @Test
    void shouldParseAsrV2SegmentMetadata() throws Exception {
        SttTranscriptEvent event = SttTranscriptEvent.fromJson(objectMapper.readTree("""
                {
                  "type": "segment",
                  "segmentId": "seg-1",
                  "source": "microphone",
                  "text": "wait",
                  "speakerId": "SPEAKER_00",
                  "speakerConfidence": 0.82,
                  "quality": "clear",
                  "overlap": false,
                  "noiseLevel": 0.13,
                  "startMs": 100,
                  "endMs": 1200,
                  "revision": 2,
                  "stable": true,
                  "latencyMs": 180,
                  "reason": "semantic-stable"
                }
                """));

        assertTrue(event.finalResult());
        assertEquals("seg-1", event.segmentId());
        assertEquals("microphone", event.source());
        assertEquals("SPEAKER_00", event.speakerId());
        assertEquals(0.82d, event.speakerConfidence());
        assertEquals("clear", event.quality());
        assertFalse(event.overlap());
        assertEquals(0.13d, event.noiseLevel());
        assertEquals(100L, event.startMs());
        assertEquals(1200L, event.endMs());
        assertEquals(2, event.revision());
        assertEquals(180L, event.latencyMs());
        assertEquals("semantic-stable", event.reason());
    }
}
