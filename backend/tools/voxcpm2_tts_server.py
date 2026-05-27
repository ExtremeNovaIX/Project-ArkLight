"""OpenBMB VoxCPM2 local HTTP sidecar.

Run this script inside an environment where `voxcpm`, `fastapi`, `uvicorn`
and `soundfile` are installed. It keeps VoxCPM2 loaded and exposes a small
HTTP API for the Java backend:

    POST /tts

The script intentionally targets VoxCPM2 only.
"""

from __future__ import annotations

import argparse
import io
import logging
import re
from pathlib import Path
from typing import Any

import soundfile as sf
import uvicorn
from fastapi import FastAPI, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel, Field

from voxcpm import VoxCPM
from voxcpm.model.utils import resolve_runtime_device

LOGGER = logging.getLogger("voxcpm2_tts")


class TtsRequest(BaseModel):
    """VoxCPM2 TTS request body."""

    text: str = Field(..., min_length=1)
    control_instruction: str = ""
    reference_wav_path: str = ""
    prompt_text: str = ""
    cfg_value: float = 2.0
    inference_timesteps: int = 10
    normalize: bool = False
    denoise: bool = False
    media_type: str = "wav"
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

    def synthesize(self, request: TtsRequest) -> tuple[str, bytes]:
        """Generate audio and return MIME type plus bytes."""
        media_type = normalize_media_type(request.media_type)
        kwargs: dict[str, Any] = {
            "text": final_text(request.text, request.control_instruction),
            "cfg_value": request.cfg_value,
            "inference_timesteps": request.inference_timesteps,
            "normalize": request.normalize,
            "denoise": request.denoise,
        }

        reference_wav_path = clean_path(request.reference_wav_path)
        prompt_text = request.prompt_text.strip()
        if reference_wav_path:
            kwargs["reference_wav_path"] = reference_wav_path
        if reference_wav_path and prompt_text:
            kwargs["prompt_wav_path"] = reference_wav_path
            kwargs["prompt_text"] = prompt_text
        kwargs.update(request.extra or {})

        wav = self.generate_with_short_audio_retry(kwargs, request.text)
        sample_rate = self.model.tts_model.sample_rate
        buffer = io.BytesIO()
        sf.write(buffer, wav, sample_rate, format=media_type.upper())
        return mime_type(media_type), buffer.getvalue()

    def generate_with_short_audio_retry(self, kwargs: dict[str, Any], original_text: str):
        """Retry suspiciously short VoxCPM2 output with text normalization."""
        sample_rate = self.model.tts_model.sample_rate
        wav = self.model.generate(**kwargs)
        if not is_suspiciously_short_audio(wav, sample_rate, original_text):
            return wav

        duration = audio_duration_seconds(wav, sample_rate)
        if not kwargs.get("normalize"):
            LOGGER.warning(
                "VoxCPM2 生成音频异常短，自动开启 normalize 重试: duration=%.3fs, text=%s",
                duration,
                original_text,
            )
            retry_kwargs = dict(kwargs)
            retry_kwargs["normalize"] = True
            retry_wav = self.model.generate(**retry_kwargs)
            if not is_suspiciously_short_audio(retry_wav, sample_rate, original_text):
                return retry_wav
            wav = retry_wav
            duration = audio_duration_seconds(wav, sample_rate)

        raise ValueError(
            f"VoxCPM2 生成音频异常短: duration={duration:.3f}s, text={original_text}"
        )


def final_text(text: str, control_instruction: str) -> str:
    """Apply VoxCPM2 voice design control syntax."""
    clean_text = text.strip()
    control = re.sub(r"[()（）]", "", (control_instruction or "")).strip()
    return f"({control}){clean_text}" if control else clean_text


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


def audio_duration_seconds(wav: Any, sample_rate: int) -> float:
    """Calculate waveform duration."""
    if sample_rate <= 0:
        return 0.0
    try:
        return float(len(wav)) / float(sample_rate)
    except TypeError:
        return 0.0


def is_suspiciously_short_audio(wav: Any, sample_rate: int, text: str) -> bool:
    """Detect bad cases where VoxCPM2 returns only a tiny WAV for a long sentence."""
    duration = audio_duration_seconds(wav, sample_rate)
    compact_text_length = len(re.sub(r"\s+", "", text or ""))
    if duration <= 0.2:
        return True
    if compact_text_length >= 20 and duration < 2.0:
        return True
    if compact_text_length >= 12 and duration < 1.2:
        return True
    return False


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
