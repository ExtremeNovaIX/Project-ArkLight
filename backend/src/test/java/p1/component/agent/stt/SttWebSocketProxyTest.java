package p1.component.agent.stt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SttWebSocketProxyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldRejectLegacySherpaJson() {
        SttWebSocketProxy.TranscriptDispatcher dispatcher = new SttWebSocketProxy.TranscriptDispatcher(
                objectMapper,
                ignored -> {
                },
                ignored -> {
                },
                () -> {
                });

        assertThrows(IllegalArgumentException.class,
                () -> dispatcher.dispatch("{\"text\":\"hello\",\"final\":false}", false));
    }

    @Test
    void shouldDispatchAsrV2SegmentWithSpeakerMetadata() throws Exception {
        List<SttTranscriptEvent> segments = new ArrayList<>();
        SttWebSocketProxy.TranscriptDispatcher dispatcher = new SttWebSocketProxy.TranscriptDispatcher(
                objectMapper,
                segments::add,
                ignored -> {
                },
                () -> {
                });

        boolean consumedEnd = dispatcher.dispatch("""
                {
                  "type":"segment",
                  "segmentId":"seg-1",
                  "source":"application",
                  "text":"hello",
                  "speakerId":"SPEAKER_00",
                  "speakerConfidence":0.81,
                  "quality":"clear",
                  "overlap":false,
                  "noiseLevel":0.1,
                  "startMs":100,
                  "endMs":900,
                  "revision":3,
                  "stable":true,
                  "latencyMs":90,
                  "reason":"semantic-stable"
                }
                """, false);

        assertFalse(consumedEnd);
        assertEquals(1, segments.size());
        SttTranscriptEvent event = segments.getFirst();
        assertEquals("segment", event.type());
        assertEquals("seg-1", event.segmentId());
        assertEquals("application", event.source());
        assertEquals("hello", event.text());
        assertTrue(event.finalResult());
        assertEquals("SPEAKER_00", event.speakerId());
        assertEquals(0.81d, event.speakerConfidence());
        assertEquals("clear", event.quality());
        assertEquals(3, event.revision());
    }

    @Test
    void shouldDispatchPartialAndDiagnosticToPartialCallback() throws Exception {
        List<SttTranscriptEvent> partials = new ArrayList<>();
        AtomicInteger streamEnds = new AtomicInteger();
        SttWebSocketProxy.TranscriptDispatcher dispatcher = new SttWebSocketProxy.TranscriptDispatcher(
                objectMapper,
                ignored -> {
                },
                partials::add,
                streamEnds::incrementAndGet);

        dispatcher.dispatch("{\"type\":\"partial\",\"text\":\"he\",\"stable\":false}", false);
        assertFalse(dispatcher.dispatch("{\"type\":\"diagnostic\",\"text\":\"latency p95=320\",\"stable\":false}", true));

        assertEquals(2, partials.size());
        assertEquals("partial", partials.get(0).type());
        assertEquals("diagnostic", partials.get(1).type());
        assertEquals(0, streamEnds.get());
    }
}
