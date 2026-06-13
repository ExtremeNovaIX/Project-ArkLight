import sys
import unittest
from pathlib import Path

import numpy as np


sys.path.insert(0, str(Path(__file__).resolve().parent))

from sherpa_qwen_sidecar import (  # noqa: E402
    SAMPLE_RATE,
    StreamingSession,
    normalize_source,
    parse_hotword_text,
    pcm16_to_float32,
)


class FakeRecognizer:
    def __init__(self, text_prefix="hello"):
        self.text_prefix = text_prefix

    def transcribe(self, samples):
        if self.text_prefix == "":
            return ""
        if samples.size < 8000:
            return ""
        return f"{self.text_prefix} {samples.size}"


class FakeSpeakerTracker:
    def __init__(self, speaker_id="SPEAKER_00", confidence=0.82, overlap=False):
        self.speaker_id = speaker_id
        self.confidence = confidence
        self.overlap = overlap
        self.identify_calls = 0
        self.enroll_calls = 0
        self.passive_calls = 0

    def identify(self, samples, enroll=True):
        self.identify_calls += 1
        if enroll:
            self.enroll_calls += 1
        else:
            self.passive_calls += 1
        if samples.size < 12000:
            return "UNKNOWN", 0.0
        if not enroll:
            return "UNKNOWN", 0.0
        return self.speaker_id, self.confidence

    def has_overlap(self, samples):
        return self.overlap


def voice_chunk(samples=1600, level=0.08, frequency=220.0):
    timeline = np.arange(samples, dtype=np.float32) / SAMPLE_RATE
    return (np.sin(2 * np.pi * frequency * timeline) * level).astype(np.float32)


def low_frequency_tone(samples=1600, level=0.08, frequency=40.0):
    timeline = np.arange(samples, dtype=np.float32) / SAMPLE_RATE
    return (np.sin(2 * np.pi * frequency * timeline) * level).astype(np.float32)


class SherpaQwenSidecarTest(unittest.TestCase):
    def test_pcm16_to_float32(self):
        samples = np.array([0, 32767, -32768], dtype=np.int16)

        converted = pcm16_to_float32(samples.tobytes())

        self.assertEqual(converted.dtype, np.float32)
        self.assertAlmostEqual(float(converted[0]), 0.0)
        self.assertGreater(float(converted[1]), 0.99)
        self.assertEqual(float(converted[2]), -1.0)

    def test_streaming_session_emits_partial_and_segment(self):
        events = []
        session = StreamingSession(
            source="microphone",
            recognizer=FakeRecognizer(),
            speaker_tracker=FakeSpeakerTracker(),
            partial_interval_ms=500,
            max_segment_ms=1500,
            emit=events.append,
        )

        chunk = voice_chunk(level=0.05)
        for _ in range(20):
            session.feed(chunk)

        event_types = [event["type"] for event in events]
        self.assertIn("partial", event_types)
        self.assertIn("segment", event_types)
        segment = next(event for event in events if event["type"] == "segment")
        self.assertEqual(segment["source"], "microphone")
        self.assertEqual(segment["speakerId"], "SPEAKER_00")
        self.assertTrue(segment["stable"])

    def test_partial_does_not_enroll_new_speaker(self):
        events = []
        tracker = FakeSpeakerTracker()
        session = StreamingSession(
            source="application",
            recognizer=FakeRecognizer(),
            speaker_tracker=tracker,
            partial_interval_ms=500,
            max_segment_ms=2000,
            emit=events.append,
        )

        chunk = voice_chunk(level=0.08)
        for _ in range(5):
            session.feed(chunk)

        partial = next(event for event in events if event["type"] == "partial")
        self.assertEqual("UNKNOWN", partial["speakerId"])
        self.assertEqual(0, tracker.enroll_calls)
        self.assertGreaterEqual(tracker.passive_calls, 1)

    def test_overlapped_segment_does_not_enroll_new_speaker(self):
        events = []
        tracker = FakeSpeakerTracker(overlap=True)
        session = StreamingSession(
            source="application",
            recognizer=FakeRecognizer(),
            speaker_tracker=tracker,
            partial_interval_ms=500,
            max_segment_ms=1000,
            emit=events.append,
        )

        chunk = voice_chunk(level=0.08)
        for _ in range(10):
            session.feed(chunk)

        segment = next(event for event in events if event["type"] == "segment")
        self.assertEqual("UNKNOWN", segment["speakerId"])
        self.assertEqual("overlapped", segment["quality"])
        self.assertEqual(0, tracker.enroll_calls)

    def test_short_application_segment_does_not_enroll_new_speaker(self):
        events = []
        tracker = FakeSpeakerTracker()
        session = StreamingSession(
            source="application",
            recognizer=FakeRecognizer(),
            speaker_tracker=tracker,
            partial_interval_ms=500,
            max_segment_ms=1200,
            emit=events.append,
        )

        chunk = voice_chunk(level=0.08)
        for _ in range(12):
            session.feed(chunk)

        segment = next(event for event in events if event["type"] == "segment")
        self.assertEqual("UNKNOWN", segment["speakerId"])
        self.assertEqual("clear", segment["quality"])
        self.assertEqual(0, tracker.enroll_calls)

    def test_non_speech_audio_does_not_emit_or_register_speaker(self):
        events = []
        tracker = FakeSpeakerTracker()
        session = StreamingSession(
            source="application",
            recognizer=FakeRecognizer(),
            speaker_tracker=tracker,
            partial_interval_ms=500,
            max_segment_ms=1000,
            emit=events.append,
        )

        chunk = low_frequency_tone(level=0.08)
        for _ in range(10):
            session.feed(chunk)

        self.assertEqual([], events)
        self.assertEqual(0, tracker.identify_calls)

    def test_empty_transcript_window_does_not_register_speaker(self):
        events = []
        tracker = FakeSpeakerTracker()
        session = StreamingSession(
            source="application",
            recognizer=FakeRecognizer(text_prefix=""),
            speaker_tracker=tracker,
            partial_interval_ms=500,
            max_segment_ms=1000,
            emit=events.append,
        )

        chunk = voice_chunk(level=0.08)
        for _ in range(10):
            session.feed(chunk)

        self.assertEqual([], events)
        self.assertEqual(0, tracker.identify_calls)

    def test_trailing_silence_flushes_completed_speech_segment(self):
        events = []
        session = StreamingSession(
            source="microphone",
            recognizer=FakeRecognizer(),
            speaker_tracker=FakeSpeakerTracker(),
            partial_interval_ms=500,
            max_segment_ms=5000,
            emit=events.append,
        )

        speech = voice_chunk(level=0.08)
        silence = np.zeros(1600, dtype=np.float32)
        for _ in range(12):
            session.feed(speech)
        for _ in range(9):
            session.feed(silence)

        segments = [event for event in events if event["type"] == "segment"]
        self.assertEqual(1, len(segments))
        self.assertEqual("speech-end", segments[0]["reason"])

    def test_normalize_source_keeps_system_output_as_application_source(self):
        self.assertEqual(normalize_source("system-output"), "application")
        self.assertEqual(normalize_source("mic"), "microphone")
        self.assertEqual(normalize_source("strange"), "unknown")

    def test_parse_hotword_text_skips_titles_notes_and_duplicates(self):
        text = """
战术行为类
开团、接团、开团
注：这里是说明，不应进入 hotwords。
peek、peek 不要的话可删、farm
"""

        self.assertEqual(["开团", "接团", "开团", "peek", "farm"], parse_hotword_text(text))


if __name__ == "__main__":
    unittest.main()
