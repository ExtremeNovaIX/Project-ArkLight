# ArcLight sherpa-qwen ASR

ArcLight STT defaults to `sherpa-qwen-onnx`: a local Windows-friendly ASR runtime that runs Qwen3-ASR ONNX through `sherpa-onnx` and labels speakers with sherpa speaker embedding models.

The backend starts `backend/tools/asr/sherpa_qwen_sidecar.py` on `127.0.0.1:6006`. Missing Python packages, missing ONNX model files, or a busy sidecar port are hard errors reported by doctor and the Qt startup/runtime panel. The backend does not silently switch to another ASR implementation.

## Bootstrap

Run:

```powershell
powershell -ExecutionPolicy Bypass -File backend/tools/asr/bootstrap-sherpa-qwen.ps1
```

This creates:

```text
backend/runtime/asr/sherpa-qwen/.venv
backend/runtime/asr/sherpa-qwen/models/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25
backend/runtime/asr/sherpa-qwen/models/diarization
```

The bootstrap downloads the Qwen3-ASR ONNX model, pyannote segmentation model, and 3D-Speaker embedding model from the sherpa-onnx release assets. Use `-SkipModelDownload` when the model files have already been placed in those directories.

## Runtime Contract

Qt sends 16 kHz PCM int16 audio to backend `/stt/stream`. The backend forwards the stream to the local sidecar. The sidecar emits ASR v2 events:

```json
{
  "type": "partial",
  "source": "microphone",
  "text": "hello",
  "speakerId": "SPEAKER_00",
  "speakerConfidence": 0.82,
  "quality": "clear",
  "overlap": false,
  "noiseLevel": 0.1,
  "stable": false,
  "latencyMs": 120
}
```

`partial` events are for fast UI and game intent routing. `segment` events are stable utterances. Only completed non-noisy, non-overlapped speech may enter RP. Speaker identity is reported through `speakerId` and `speakerConfidence`; low speaker confidence does not by itself make a speech segment unclear.

## Configuration

Default values:

```text
STT_ENGINE=sherpa-qwen-onnx
STT_SHERPA_QWEN_PYTHON_EXE=backend/runtime/asr/sherpa-qwen/.venv/Scripts/python.exe
STT_SHERPA_QWEN_MODEL_DIR=backend/runtime/asr/sherpa-qwen/models/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25
STT_SHERPA_DIARIZATION_MODEL_DIR=backend/runtime/asr/sherpa-qwen/models/diarization
STT_PARTIAL_INTERVAL_MS=500
STT_MAX_SEGMENT_MS=3000
```

Equivalent JVM properties are:

```text
-Dstt.engine=sherpa-qwen-onnx
-Dstt.sherpa-qwen.python.executable=...
-Dstt.sherpa-qwen.model-dir=...
-Dstt.sherpa-qwen.diarization-model-dir=...
-Dstt.partial-interval-ms=500
-Dstt.max-segment-ms=3000
```

## Verification

```powershell
powershell -ExecutionPolicy Bypass -File scripts/verify.ps1 -Scope quick
```

For runtime/model presence:

```powershell
backend/runtime/asr/sherpa-qwen/.venv/Scripts/python.exe backend/tools/asr/smoke_sherpa_qwen_sidecar.py
```
