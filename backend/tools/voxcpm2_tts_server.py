"""OpenBMB VoxCPM2 local HTTP sidecar.

Run this script inside an environment where `voxcpm`, `fastapi`, `uvicorn`
and `soundfile` are installed. It keeps VoxCPM2 loaded and exposes two small
HTTP APIs for the Java backend:

    POST /tts
    POST /tts-stream

The script intentionally targets VoxCPM2 only.
"""

from __future__ import annotations

import argparse
import base64
import io
import json
import logging
import queue
import re
import threading
from pathlib import Path
from typing import Any, Callable, Generator

import soundfile as sf
import uvicorn
import numpy as np
from fastapi import FastAPI, HTTPException
from fastapi.responses import Response, StreamingResponse
from pydantic import BaseModel, ConfigDict, Field

from voxcpm import VoxCPM
from voxcpm.model.utils import resolve_runtime_device

LOGGER = logging.getLogger("voxcpm2_tts")
STREAM_MEDIA_TYPE = "application/x-ndjson"
PCM_MEDIA_TYPE = "audio/pcm;format=s16le;channels=1"


class TtsRequest(BaseModel):
    """VoxCPM2 TTS request body."""

    model_config = ConfigDict(extra="forbid")

    text: str = Field(..., min_length=1)
    reference_wav_path: str = ""
    prompt_text: str = ""
    cfg_value: float = 2.0
    inference_timesteps: int = 10
    normalize: bool = False
    denoise: bool = False
    media_type: str = "wav"
    streaming: bool = False
    badcase_retry_attempts: int = 1
    extra: dict[str, Any] = Field(default_factory=dict)


class VoxCpm2Server:
    """Long-lived VoxCPM2 model holder."""

    def __init__(self, model_id: str, device: str, load_denoiser: bool, optimize: bool) -> None:
        self.device = resolve_runtime_device(device, "cuda")
        self.model = VoxCPM.from_pretrained(
            model_id,
            load_denoiser=load_denoiser,
            optimize=optimize,
            device=self.device,
        )
        self._prompt_cache: dict[tuple[Any, ...], Any] = {}
        self._patch_prompt_cache()
        self._jobs: queue.Queue[tuple[Callable[[], Any], queue.Queue[tuple[str, Any]]]] = queue.Queue()
        self._worker = threading.Thread(target=self._worker_loop, name="voxcpm2-model-worker", daemon=True)
        self._worker.start()

    def synthesize(self, request: TtsRequest) -> tuple[str, bytes]:
        """Generate audio and return MIME type plus bytes."""
        return self._run_sync(lambda: self._synthesize_on_worker(request))

    def synthesize_stream(self, request: TtsRequest) -> Generator[bytes, None, None]:
        """Generate audio chunks as NDJSON lines."""
        yield from self._run_stream(lambda: self._synthesize_stream_on_worker(request))

    def _synthesize_on_worker(self, request: TtsRequest) -> tuple[str, bytes]:
        """Generate a full audio file inside the fixed model worker."""
        media_type = normalize_media_type(request.media_type)
        kwargs = self.build_generation_kwargs(request)
        wav = self.generate_with_audio_length_retry(kwargs, request.text, request.badcase_retry_attempts)
        sample_rate = self.model.tts_model.sample_rate
        return mime_type(media_type), encode_audio(wav, sample_rate, media_type)

    def _synthesize_stream_on_worker(self, request: TtsRequest) -> Generator[bytes, None, None]:
        """Generate streaming audio chunks inside the fixed model worker."""
        kwargs = self.build_generation_kwargs(request)
        kwargs["retry_badcase"] = False
        sample_rate = self.model.tts_model.sample_rate
        for index, wav in enumerate(self.model.generate_streaming(**kwargs)):
            audio = encode_pcm_s16le(wav)
            payload = {
                "index": index,
                "media_type": PCM_MEDIA_TYPE,
                "sample_rate": sample_rate,
                "audio_base64": base64.b64encode(audio).decode("ascii"),
            }
            yield (json.dumps(payload, separators=(",", ":")) + "\n").encode("utf-8")

    def build_generation_kwargs(self, request: TtsRequest) -> dict[str, Any]:
        """Build VoxCPM generation keyword arguments."""
        reference_wav_path = clean_path(request.reference_wav_path)
        prompt_text = request.prompt_text.strip()
        hifi_clone = bool(reference_wav_path and prompt_text)
        kwargs: dict[str, Any] = {
            "text": request.text.strip(),
            "cfg_value": request.cfg_value,
            "inference_timesteps": request.inference_timesteps,
            "normalize": request.normalize,
            "denoise": request.denoise,
        }

        if reference_wav_path:
            kwargs["reference_wav_path"] = reference_wav_path
        if hifi_clone:
            kwargs["prompt_wav_path"] = reference_wav_path
            kwargs["prompt_text"] = prompt_text
        kwargs.update(request.extra or {})
        return kwargs

    def generate_with_audio_length_retry(self, kwargs: dict[str, Any], original_text: str, max_attempts: int):
        """Retry suspiciously short or long VoxCPM2 badcase output."""
        sample_rate = self.model.tts_model.sample_rate
        retry_kwargs = dict(kwargs)
        last_issue = "unknown"
        last_duration = 0.0
        attempts = max(1, max_attempts)

        for attempt in range(1, attempts + 1):
            wav = self.model.generate(**retry_kwargs)
            issue = audio_length_issue(wav, sample_rate, original_text)
            if issue is None:
                return wav

            last_issue = issue
            last_duration = audio_duration_seconds(wav, sample_rate)
            if attempt >= attempts:
                break

            if issue.startswith("too_short") and not retry_kwargs.get("normalize"):
                retry_kwargs["normalize"] = True
            LOGGER.warning(
                "VoxCPM2 suspicious audio, retrying: attempt=%d/%d, issue=%s, duration=%.3fs, text=%s",
                attempt,
                attempts,
                issue,
                last_duration,
                original_text,
            )

        raise ValueError(
            f"VoxCPM2 suspicious audio: issue={last_issue}, duration={last_duration:.3f}s, text={original_text}"
        )

    def _patch_prompt_cache(self) -> None:
        """Cache encoded prompt/reference audio by path and file mtime."""
        original_build_prompt_cache = self.model.tts_model.build_prompt_cache

        def cached_build_prompt_cache(*args: Any, **kwargs: Any) -> Any:
            key = prompt_cache_key(args, kwargs)
            if key not in self._prompt_cache:
                self._prompt_cache[key] = original_build_prompt_cache(*args, **kwargs)
            return self._prompt_cache[key]

        self.model.tts_model.build_prompt_cache = cached_build_prompt_cache

    def _worker_loop(self) -> None:
        """Run all model operations on one stable thread for torch.compile/CUDA graphs."""
        while True:
            task, result_queue = self._jobs.get()
            try:
                result = task()
                if isinstance(result, tuple):
                    result_queue.put(("result", result))
                    continue
                for chunk in result:
                    result_queue.put(("chunk", chunk))
                result_queue.put(("done", None))
            except Exception as exc:  # noqa: BLE001 - relay to request thread
                result_queue.put(("error", exc))

    def _run_sync(self, task: Callable[[], Any]) -> Any:
        result_queue: queue.Queue[tuple[str, Any]] = queue.Queue()
        self._jobs.put((task, result_queue))
        kind, value = result_queue.get()
        if kind == "error":
            raise value
        return value

    def _run_stream(self, task: Callable[[], Generator[bytes, None, None]]) -> Generator[bytes, None, None]:
        result_queue: queue.Queue[tuple[str, Any]] = queue.Queue()
        self._jobs.put((task, result_queue))
        while True:
            kind, value = result_queue.get()
            if kind == "chunk":
                yield value
            elif kind == "done":
                return
            elif kind == "error":
                raise value


def clean_path(path: str) -> str:
    """Normalize optional path values."""
    if path is None or not path.strip():
        return ""
    return str(Path(path.strip()).expanduser())


def normalize_media_type(value: str) -> str:
    """Normalize supported audio container format."""
    normalized = (value or "wav").strip().lower()
    return normalized if normalized in {"wav", "flac"} else "wav"


def mime_type(format_name: str) -> str:
    """Map audio format to MIME type."""
    return "audio/flac" if format_name == "flac" else "audio/wav"


def encode_audio(wav: Any, sample_rate: int, media_type: str) -> bytes:
    """Encode waveform into a self-contained audio file."""
    buffer = io.BytesIO()
    sf.write(buffer, wav, sample_rate, format=media_type.upper())
    return buffer.getvalue()


def encode_pcm_s16le(wav: Any) -> bytes:
    """Encode a mono waveform as little-endian signed 16-bit PCM."""
    samples = np.asarray(wav, dtype=np.float32).reshape(-1)
    clipped = np.clip(samples, -1.0, 1.0)
    return (clipped * 32767.0).astype("<i2").tobytes()


def prompt_cache_key(args: tuple[Any, ...], kwargs: dict[str, Any]) -> tuple[Any, ...]:
    """Build a cache key that changes when prompt/reference files change."""
    path_values = [
        kwargs.get("prompt_wav_path"),
        kwargs.get("reference_wav_path"),
    ]
    path_signatures = tuple(file_signature(path) for path in path_values)
    kw_items = tuple(sorted((key, safe_cache_value(value)) for key, value in kwargs.items()))
    arg_items = tuple(safe_cache_value(value) for value in args)
    return arg_items, kw_items, path_signatures


def file_signature(path: Any) -> tuple[str, int | None]:
    """Return a cache signature for a file path."""
    if path is None or not str(path).strip():
        return "", None
    normalized = str(Path(str(path)).expanduser())
    try:
        return normalized, Path(normalized).stat().st_mtime_ns
    except OSError:
        return normalized, None


def safe_cache_value(value: Any) -> Any:
    """Convert values into hashable cache-key parts."""
    if isinstance(value, dict):
        return tuple(sorted((key, safe_cache_value(item)) for key, item in value.items()))
    if isinstance(value, list):
        return tuple(safe_cache_value(item) for item in value)
    return value


def audio_duration_seconds(wav: Any, sample_rate: int) -> float:
    """Calculate waveform duration."""
    if sample_rate <= 0:
        return 0.0
    try:
        return float(len(wav)) / float(sample_rate)
    except TypeError:
        return 0.0


def audio_length_issue(wav: Any, sample_rate: int, text: str) -> str | None:
    """Detect bad cases where VoxCPM2 returns implausibly short or long audio."""
    duration = audio_duration_seconds(wav, sample_rate)
    compact_text_length = len(re.sub(r"\s+", "", text or ""))
    if duration <= 0.2:
        return "too_short: <=0.2s"
    min_duration = expected_min_duration_seconds(compact_text_length)
    if duration < min_duration:
        return f"too_short: <{min_duration:.3f}s"
    max_duration = expected_max_duration_seconds(compact_text_length)
    if duration > max_duration:
        return f"too_long: >{max_duration:.3f}s"
    return None


def expected_min_duration_seconds(compact_text_length: int) -> float:
    """Estimate a conservative lower bound for one TTS block."""
    if compact_text_length >= 20:
        return max(2.0, compact_text_length * 0.07)
    if compact_text_length >= 12:
        return 1.2
    return 0.2


def expected_max_duration_seconds(compact_text_length: int) -> float:
    """Estimate a conservative upper bound for one TTS block."""
    if compact_text_length <= 0:
        return 8.0
    return max(10.0, compact_text_length * 0.32 + 3.0)


def create_app(model_id: str, device: str, load_denoiser: bool, optimize: bool) -> FastAPI:
    """Create FastAPI app and load VoxCPM2 once."""
    server = VoxCpm2Server(
        model_id=model_id,
        device=device,
        load_denoiser=load_denoiser,
        optimize=optimize,
    )
    app = FastAPI(title="VoxCPM2 TTS Sidecar")

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok", "model": model_id, "device": server.device}

    @app.post("/tts")
    def tts(request: TtsRequest) -> Response:
        try:
            content_type, audio = server.synthesize(request)
            return Response(content=audio, media_type=content_type)
        except Exception as exc:  # noqa: BLE001 - surface sidecar failure to Java
            raise HTTPException(status_code=500, detail=str(exc)) from exc

    @app.post("/tts-stream")
    def tts_stream(request: TtsRequest) -> StreamingResponse:
        try:
            return StreamingResponse(server.synthesize_stream(request), media_type=STREAM_MEDIA_TYPE)
        except Exception as exc:  # noqa: BLE001 - surface sidecar failure to Java
            raise HTTPException(status_code=500, detail=str(exc)) from exc

    return app


def main() -> None:
    """CLI entrypoint."""
    parser = argparse.ArgumentParser(description="OpenBMB VoxCPM2 TTS HTTP sidecar")
    parser.add_argument("--model-id", default="openbmb/VoxCPM2")
    parser.add_argument("--device", default="auto")
    parser.add_argument("--load-denoiser", action="store_true")
    parser.add_argument("--no-optimize", action="store_true")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8810)
    args = parser.parse_args()

    app = create_app(
        model_id=args.model_id,
        device=args.device,
        load_denoiser=args.load_denoiser,
        optimize=not args.no_optimize,
    )
    uvicorn.run(app, host=args.host, port=args.port)


if __name__ == "__main__":
    main()
