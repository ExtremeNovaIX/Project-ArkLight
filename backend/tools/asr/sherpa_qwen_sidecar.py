#!/usr/bin/env python3
import argparse
import asyncio
import json
import logging
import math
import re
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional

import numpy as np

try:
    import websockets
except ModuleNotFoundError:
    websockets = None


LOG = logging.getLogger("arclight.sherpa_qwen_asr")
SAMPLE_RATE = 16000
MIN_TRANSCRIBE_MS = 500
MIN_SPEAKER_MS = 750
MIN_SPEAKER_ENROLL_MS = 1200
MIN_SPEAKER_ENROLL_LEVEL = 0.04
MAX_SPEAKER_ENROLL_LEVEL = 0.80
SPEAKER_CHANGE_MIN_MS = 900
MIN_SPEAKER_ENROLL_TEXT_CHARS = 4
MAX_SPEAKER_ENROLL_CLIPPED_RATIO = 0.02
SPEECH_MIN_LEVEL = 0.015
SPEECH_MAX_LEVEL = 0.95
SPEECH_BAND_LOW_HZ = 85.0
SPEECH_BAND_HIGH_HZ = 3800.0
SPEECH_MIN_BAND_RATIO = 0.50
ENDPOINT_SILENCE_MS = 900
HOTWORD_SPLIT_PATTERN = re.compile(r"[,，、;；]+")


def now_ms() -> int:
    return int(time.time() * 1000)


def event_json(payload: dict) -> str:
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


def normalize_text(text: str) -> str:
    return " ".join((text or "").strip().split())


def pcm16_to_float32(pcm: bytes) -> np.ndarray:
    if len(pcm) < 2:
        return np.zeros(0, dtype=np.float32)
    usable = len(pcm) - (len(pcm) % 2)
    return np.frombuffer(pcm[:usable], dtype=np.int16).astype(np.float32) / 32768.0


def rms_level(samples: np.ndarray) -> float:
    if samples.size == 0:
        return 0.0
    value = float(np.sqrt(np.mean(np.square(samples))))
    return max(0.0, min(1.0, value * 8.0))


def speech_band_ratio(samples: np.ndarray) -> float:
    if samples.size < 32:
        return 0.0
    centered = samples.astype(np.float32, copy=False) - float(np.mean(samples))
    window = np.hanning(samples.size).astype(np.float32)
    spectrum = np.fft.rfft(centered * window)
    power = np.square(np.abs(spectrum))
    frequencies = np.fft.rfftfreq(samples.size, d=1.0 / SAMPLE_RATE)
    non_dc = frequencies > 0.0
    total_power = float(np.sum(power[non_dc]))
    if total_power <= 1e-12:
        return 0.0
    speech_band = (frequencies >= SPEECH_BAND_LOW_HZ) & (frequencies <= SPEECH_BAND_HIGH_HZ)
    return max(0.0, min(1.0, float(np.sum(power[speech_band]) / total_power)))


def looks_like_speech(samples: np.ndarray) -> bool:
    level = rms_level(samples)
    if level < SPEECH_MIN_LEVEL or level > SPEECH_MAX_LEVEL:
        return False
    return speech_band_ratio(samples) >= SPEECH_MIN_BAND_RATIO


class SherpaQwenRecognizer:
    def __init__(self, recognizer):
        self._recognizer = recognizer

    @classmethod
    def load(cls, args):
        import sherpa_onnx

        model_dir = Path(args.qwen_model_dir)
        hotwords = load_hotwords(args)
        recognizer = sherpa_onnx.OfflineRecognizer.from_qwen3_asr(
            conv_frontend=str(model_dir / "conv_frontend.onnx"),
            encoder=str(first_existing(model_dir, "encoder.int8.onnx", "encoder.onnx")),
            decoder=str(first_existing(model_dir, "decoder.int8.onnx", "decoder.onnx")),
            tokenizer=str(model_dir / "tokenizer"),
            num_threads=args.num_threads,
            sample_rate=SAMPLE_RATE,
            feature_dim=128,
            provider=args.provider,
            max_new_tokens=args.max_new_tokens,
            hotwords=hotwords,
        )
        if hotwords:
            LOG.info("Loaded %s sherpa-qwen hotwords", len(hotwords.split(",")))
        return cls(recognizer)

    def transcribe(self, samples: np.ndarray) -> str:
        if samples.size < SAMPLE_RATE * MIN_TRANSCRIBE_MS / 1000:
            return ""
        stream = self._recognizer.create_stream()
        stream.accept_waveform(SAMPLE_RATE, samples.astype(np.float32, copy=False))
        self._recognizer.decode_stream(stream)
        result = stream.result
        text = getattr(result, "text", "")
        return normalize_text(text)


class SpeakerTracker:
    def __init__(self, extractor, manager, diarizer, threshold: float, max_speakers: int):
        self.extractor = extractor
        self.manager = manager
        self.diarizer = diarizer
        self.threshold = threshold
        self.max_speakers = max_speakers
        self.next_index = 0

    @classmethod
    def load(cls, args):
        import sherpa_onnx

        diarization_dir = Path(args.diarization_model_dir)
        embedding_model = resolve_embedding_model(diarization_dir)
        config = sherpa_onnx.SpeakerEmbeddingExtractorConfig(
            model=str(embedding_model),
            num_threads=args.num_threads,
            provider=args.provider,
        )
        extractor = sherpa_onnx.SpeakerEmbeddingExtractor(config)
        manager = sherpa_onnx.SpeakerEmbeddingManager(extractor.dim)
        segmentation_config = sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
            pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(
                model=str(resolve_segmentation_model(diarization_dir))
            ),
            num_threads=args.num_threads,
            provider=args.provider,
        )
        diarization_config = sherpa_onnx.OfflineSpeakerDiarizationConfig(
            segmentation=segmentation_config,
            embedding=config,
            clustering=sherpa_onnx.FastClusteringConfig(num_clusters=-1, threshold=args.speaker_threshold),
        )
        diarizer = sherpa_onnx.OfflineSpeakerDiarization(diarization_config)
        return cls(extractor, manager, diarizer, args.speaker_threshold, args.max_speakers)

    def identify(self, samples: np.ndarray, enroll: bool = True) -> tuple[str, float]:
        if samples.size < SAMPLE_RATE * MIN_SPEAKER_MS / 1000:
            return "UNKNOWN", 0.0

        stream = self.extractor.create_stream()
        stream.accept_waveform(SAMPLE_RATE, samples.astype(np.float32, copy=False))
        if not self.extractor.is_ready(stream):
            return "UNKNOWN", 0.0

        embedding = self.extractor.compute(stream)
        matched = self.manager.search(embedding, self.threshold)
        if matched:
            return matched, clamp_score(self.manager.score(matched, embedding))

        if not enroll or self.next_index >= self.max_speakers:
            return "UNKNOWN", 0.0

        speaker_id = f"SPEAKER_{self.next_index:02d}"
        self.next_index += 1
        self.manager.add(speaker_id, embedding)
        return speaker_id, 0.65

    def has_overlap(self, samples: np.ndarray) -> bool:
        if samples.size < SAMPLE_RATE:
            return False
        result = self.diarizer.process(samples.astype(np.float32, copy=False))
        return result.num_speakers > 1


@dataclass
class SegmentSnapshot:
    samples: np.ndarray
    start_ms: int
    end_ms: int
    reason: str
    speakers: set[str]


class StreamingSession:
    def __init__(
        self,
        source: str,
        recognizer: SherpaQwenRecognizer,
        speaker_tracker: SpeakerTracker,
        partial_interval_ms: int,
        max_segment_ms: int,
        emit: Callable[[dict], None],
    ):
        self.source = source
        self.recognizer = recognizer
        self.speaker_tracker = speaker_tracker
        self.partial_interval_ms = partial_interval_ms
        self.max_segment_ms = max_segment_ms
        self.emit = emit
        self.segment_chunks: list[np.ndarray] = []
        self.segment_start_ms = -1
        self.total_samples = 0
        self.last_partial_ms = 0
        self.revision = 0
        self.last_partial_text = ""
        self.last_speaker_id = "UNKNOWN"
        self.segment_speakers: set[str] = set()
        self.trailing_silence_ms = 0

    def feed(self, samples: np.ndarray) -> None:
        if samples.size == 0:
            return
        previous_ms = self.audio_time_ms()
        self.total_samples += int(samples.size)
        current_ms = self.audio_time_ms()

        if not looks_like_speech(samples):
            if self.segment_chunks:
                self.trailing_silence_ms += current_ms - previous_ms
                if self.trailing_silence_ms >= ENDPOINT_SILENCE_MS:
                    self.flush("speech-end", end_ms=current_ms - self.trailing_silence_ms)
            return

        self.trailing_silence_ms = 0
        if self.segment_start_ms < 0:
            self.segment_start_ms = previous_ms
        self.segment_chunks.append(samples)

        if current_ms - self.last_partial_ms >= self.partial_interval_ms:
            self.emit_partial(current_ms)
            self.last_partial_ms = current_ms

        if current_ms - self.segment_start_ms >= self.max_segment_ms:
            self.flush("max-window")

    def finish(self) -> None:
        self.flush("done")

    def audio_time_ms(self) -> int:
        return int(self.total_samples * 1000 / SAMPLE_RATE)

    def current_audio(self) -> np.ndarray:
        if not self.segment_chunks:
            return np.zeros(0, dtype=np.float32)
        return np.concatenate(self.segment_chunks)

    def should_split_for_speaker_change(self, current_ms: int, speaker_id: str) -> bool:
        if speaker_id == "UNKNOWN" or self.last_speaker_id in ("UNKNOWN", speaker_id):
            return False
        return current_ms - self.segment_start_ms >= SPEAKER_CHANGE_MIN_MS

    def emit_partial(self, current_ms: int) -> None:
        started = now_ms()
        samples = self.current_audio()
        text = self.recognizer.transcribe(samples)
        if not text:
            return
        speaker_id, speaker_confidence = self.speaker_tracker.identify(last_samples(samples, 1200), enroll=False)
        if speaker_id != "UNKNOWN":
            self.last_speaker_id = speaker_id
            self.segment_speakers.add(speaker_id)
        if text == self.last_partial_text and current_ms - self.last_partial_ms < 1000:
            return
        self.last_partial_text = text
        self.revision += 1
        overlap = len(self.segment_speakers) > 1
        quality = quality_for(samples, speaker_confidence, overlap)
        self.emit({
            "type": "partial",
            "segmentId": active_segment_id(self.segment_start_ms),
            "source": self.source,
            "text": text,
            "speakerId": best_speaker(speaker_id, self.last_speaker_id),
            "speakerConfidence": speaker_confidence,
            "quality": quality,
            "overlap": overlap,
            "noiseLevel": rms_level(samples),
            "startMs": self.segment_start_ms,
            "endMs": current_ms,
            "revision": self.revision,
            "stable": False,
            "latencyMs": now_ms() - started,
            "reason": "rolling-partial",
        })

    def flush(self, reason: str, end_ms: Optional[int] = None) -> None:
        samples = self.current_audio()
        if samples.size == 0:
            return
        snapshot = SegmentSnapshot(
            samples=samples,
            start_ms=self.segment_start_ms,
            end_ms=self.audio_time_ms() if end_ms is None else end_ms,
            reason=reason,
            speakers=set(self.segment_speakers),
        )
        self.segment_chunks = []
        self.segment_start_ms = self.audio_time_ms() if reason == "max-window" else -1
        self.segment_speakers = set()
        self.last_partial_text = ""
        self.trailing_silence_ms = 0
        self.emit_segment(snapshot)

    def emit_segment(self, snapshot: SegmentSnapshot) -> None:
        started = now_ms()
        text = self.recognizer.transcribe(snapshot.samples)
        if not text:
            return
        diarized_overlap = tracker_has_overlap(self.speaker_tracker, snapshot.samples)
        overlap = diarized_overlap or len(snapshot.speakers) > 1
        enroll = should_enroll_speaker(snapshot.samples, text, overlap, self.source)
        speaker_id, confidence = self.speaker_tracker.identify(snapshot.samples, enroll=enroll)
        if speaker_id != "UNKNOWN":
            self.last_speaker_id = speaker_id
        quality = quality_for(snapshot.samples, confidence, overlap)
        self.revision += 1
        self.emit({
            "type": "segment",
            "segmentId": str(uuid.uuid4()),
            "source": self.source,
            "text": text,
            "speakerId": best_speaker(speaker_id, self.last_speaker_id),
            "speakerConfidence": confidence,
            "quality": quality,
            "overlap": overlap,
            "noiseLevel": rms_level(snapshot.samples),
            "startMs": snapshot.start_ms,
            "endMs": snapshot.end_ms,
            "revision": self.revision,
            "stable": True,
            "latencyMs": now_ms() - started,
            "reason": snapshot.reason,
        })


async def handle_client(websocket, recognizer, speaker_tracker, args):
    session: Optional[StreamingSession] = None

    async def emit(payload: dict) -> None:
        await websocket.send(event_json(payload))

    try:
        async for message in websocket:
            if isinstance(message, str):
                lowered = message.strip().lower()
                if lowered in ("done", "eof"):
                    if session is not None:
                        session.finish()
                    await emit({"type": "diagnostic", "text": "", "stable": False, "reason": "stream-end"})
                    continue
                if session is None:
                    config = parse_client_config(message)
                    if config.get("protocol") != "asr-v2":
                        await emit_error(emit, "Unsupported STT protocol. Expected asr-v2.")
                        continue
                    sample_rate = int(config.get("sample_rate", SAMPLE_RATE))
                    if sample_rate != SAMPLE_RATE:
                        await emit_error(emit, f"Unsupported sample_rate={sample_rate}; expected 16000.")
                        continue
                    source = normalize_source(str(config.get("source", "unknown")))
                    session = StreamingSession(
                        source=source,
                        recognizer=recognizer,
                        speaker_tracker=speaker_tracker,
                        partial_interval_ms=args.partial_interval_ms,
                        max_segment_ms=args.max_segment_ms,
                        emit=lambda payload: asyncio.create_task(emit(payload)),
                    )
                    await emit({
                        "type": "diagnostic",
                        "text": "",
                        "stable": False,
                        "source": source,
                        "reason": "sherpa-qwen-ready",
                    })
                continue

            if isinstance(message, bytes) and session is not None:
                session.feed(pcm16_to_float32(message))
    except Exception as exc:
        if websockets is not None and isinstance(exc, websockets.exceptions.ConnectionClosed):
            LOG.info("Qt STT client disconnected")
            return
        LOG.exception("STT client handling failed")
        try:
            await emit_error(emit, str(exc))
        except Exception:
            pass


async def emit_error(emit: Callable[[dict], object], message: str) -> None:
    result = emit({
        "type": "error",
        "text": message,
        "stable": False,
        "quality": "uncertain",
        "reason": message,
    })
    if asyncio.iscoroutine(result):
        await result


def parse_client_config(message: str) -> dict:
    try:
        parsed = json.loads(message)
        return parsed if isinstance(parsed, dict) else {}
    except json.JSONDecodeError:
        return {}


def normalize_source(source: str) -> str:
    value = (source or "").strip().lower()
    if value in ("mic", "microphone"):
        return "microphone"
    if value in ("app", "application", "process", "system", "system-output", "system_output"):
        return "application"
    return "unknown"


def quality_for(samples: np.ndarray, speaker_confidence: float, overlap: bool) -> str:
    if samples.size == 0:
        return "noisy"
    if overlap:
        return "overlapped"
    if not looks_like_speech(samples):
        return "noisy"
    return "clear"


def clipped_ratio(samples: np.ndarray) -> float:
    if samples.size == 0:
        return 1.0
    return float(np.mean(np.abs(samples) >= 0.98))


def useful_text_length(text: str) -> int:
    return len(re.sub(r"\s+", "", normalize_text(text)))


def should_enroll_speaker(samples: np.ndarray, text: str, overlap: bool, source: str = "unknown") -> bool:
    if overlap:
        return False
    source_value = normalize_source(source)
    min_duration_ms = 1800 if source_value == "application" else MIN_SPEAKER_ENROLL_MS
    if samples.size < SAMPLE_RATE * min_duration_ms / 1000:
        return False
    level = rms_level(samples)
    if level < MIN_SPEAKER_ENROLL_LEVEL or level > MAX_SPEAKER_ENROLL_LEVEL:
        return False
    if clipped_ratio(samples) > MAX_SPEAKER_ENROLL_CLIPPED_RATIO:
        return False
    return useful_text_length(text) >= MIN_SPEAKER_ENROLL_TEXT_CHARS


def load_hotwords(args) -> str:
    values: list[str] = []
    raw_hotwords = getattr(args, "hotwords", "")
    if raw_hotwords:
        values.extend(parse_hotword_text(raw_hotwords))

    hotwords_file = getattr(args, "hotwords_file", "")
    if hotwords_file:
        path = Path(hotwords_file)
        if path.is_file():
            values.extend(parse_hotword_text(path.read_text(encoding="utf-8")))
        else:
            LOG.warning("Hotwords file does not exist: %s", path)

    unique: list[str] = []
    seen: set[str] = set()
    for value in values:
        key = value.casefold()
        if key not in seen:
            seen.add(key)
            unique.append(value)
    limit = int(getattr(args, "max_hotwords", 300) or 0)
    if limit > 0 and len(unique) > limit:
        LOG.info("Truncated sherpa-qwen hotwords from %s to %s", len(unique), limit)
        unique = unique[:limit]
    return ",".join(unique)


def parse_hotword_text(text: str) -> list[str]:
    values: list[str] = []
    for raw_line in (text or "").splitlines():
        line = strip_hotword_comment(raw_line).strip()
        if not line or should_skip_hotword_line(line):
            continue
        for item in HOTWORD_SPLIT_PATTERN.split(line):
            value = normalize_text(item)
            if is_usable_hotword(value):
                values.append(value)
    return values


def strip_hotword_comment(line: str) -> str:
    if "#" in line:
        return line.split("#", 1)[0]
    return line


def should_skip_hotword_line(line: str) -> bool:
    stripped = line.strip()
    if stripped.startswith(("注:", "注：")):
        return True
    return stripped.endswith("类") and HOTWORD_SPLIT_PATTERN.search(stripped) is None and len(stripped) <= 12


def is_usable_hotword(value: str) -> bool:
    if not value:
        return False
    if len(value) > 24:
        return False
    if any(marker in value for marker in ("不要", "如果", "保留", "删除", "资料", "确实")):
        return False
    return True


def best_speaker(candidate: str, fallback: str) -> str:
    if candidate and candidate != "UNKNOWN":
        return candidate
    if fallback and fallback != "UNKNOWN":
        return fallback
    return "UNKNOWN"


def clamp_score(value: float) -> float:
    if math.isnan(value) or math.isinf(value):
        return 0.0
    return max(0.0, min(1.0, float(value)))


def last_samples(samples: np.ndarray, duration_ms: int) -> np.ndarray:
    count = int(SAMPLE_RATE * duration_ms / 1000)
    if samples.size <= count:
        return samples
    return samples[-count:]


def active_segment_id(start_ms: int) -> str:
    return f"active-{start_ms}"


def first_existing(model_dir: Path, preferred: str, fallback: str) -> Path:
    preferred_path = model_dir / preferred
    if preferred_path.is_file():
        return preferred_path
    return model_dir / fallback


def resolve_embedding_model(model_dir: Path) -> Path:
    candidates = [
        model_dir / "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
        model_dir / "embedding" / "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
        model_dir / "embedding.onnx",
    ]
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    return candidates[0]


def resolve_segmentation_model(model_dir: Path) -> Path:
    candidates = [
        model_dir / "sherpa-onnx-pyannote-segmentation-3-0" / "model.int8.onnx",
        model_dir / "sherpa-onnx-pyannote-segmentation-3-0" / "model.onnx",
        model_dir / "segmentation" / "model.int8.onnx",
        model_dir / "segmentation" / "model.onnx",
    ]
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    return candidates[0]


def tracker_has_overlap(tracker: object, samples: np.ndarray) -> bool:
    has_overlap = getattr(tracker, "has_overlap", None)
    if not callable(has_overlap):
        return False
    try:
        return bool(has_overlap(samples))
    except Exception as exc:
        LOG.debug("Diarization overlap detection failed: %s", exc)
        return False


def validate_files(args) -> None:
    qwen_dir = Path(args.qwen_model_dir)
    diarization_dir = Path(args.diarization_model_dir)
    required = [
        qwen_dir / "conv_frontend.onnx",
        first_existing(qwen_dir, "encoder.int8.onnx", "encoder.onnx"),
        first_existing(qwen_dir, "decoder.int8.onnx", "decoder.onnx"),
        qwen_dir / "tokenizer" / "vocab.json",
        qwen_dir / "tokenizer" / "merges.txt",
        qwen_dir / "tokenizer" / "tokenizer_config.json",
        resolve_segmentation_model(diarization_dir),
        resolve_embedding_model(diarization_dir),
    ]
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise RuntimeError("Missing sherpa-qwen model files: " + "; ".join(missing))


async def main_async(args) -> None:
    if websockets is None:
        raise RuntimeError("Missing Python package: websockets")
    validate_files(args)
    recognizer = SherpaQwenRecognizer.load(args)
    speaker_tracker = SpeakerTracker.load(args)
    LOG.info(
        "Starting sherpa-qwen ASR sidecar host=%s port=%s qwen=%s diarization=%s",
        args.host,
        args.port,
        args.qwen_model_dir,
        args.diarization_model_dir,
    )
    async with websockets.serve(
        lambda ws: handle_client(ws, recognizer, speaker_tracker, args),
        args.host,
        args.port,
        ping_interval=30,
        ping_timeout=10,
        max_size=8 * 1024 * 1024,
    ):
        await asyncio.Future()


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="ArcLight sherpa-onnx Qwen3-ASR ASR v2 sidecar")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=6006)
    parser.add_argument("--qwen-model-dir", required=True)
    parser.add_argument("--diarization-model-dir", required=True)
    parser.add_argument("--partial-interval-ms", type=int, default=500)
    parser.add_argument("--max-segment-ms", type=int, default=3000)
    parser.add_argument("--speaker-threshold", type=float, default=0.55)
    parser.add_argument("--max-speakers", type=int, default=8)
    parser.add_argument("--provider", default="cpu")
    parser.add_argument("--num-threads", type=int, default=3)
    parser.add_argument("--max-new-tokens", type=int, default=512)
    parser.add_argument("--hotwords", default="")
    parser.add_argument("--hotwords-file", default="")
    parser.add_argument("--max-hotwords", type=int, default=300)
    return parser


def configure_logging() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
    )


def main() -> None:
    configure_logging()
    args = build_parser().parse_args()
    try:
        asyncio.run(main_async(args))
    except Exception as exc:
        LOG.exception("sherpa-qwen sidecar failed: %s", exc)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
