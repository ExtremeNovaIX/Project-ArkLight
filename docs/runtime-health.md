# Arklight Runtime Health

This document records stable runtime checks that should stay aligned with the backend doctor API.

## Doctor API

The backend exposes:

```text
GET /api/doctor/status
```

The response shape is documented in:

```text
docs/contracts/arclight-api.openapi.json
```

The Qt frontend calls this endpoint during startup. If the backend returns `WARN` or `ERROR` checks, Qt shows a runtime warning dialog after the boot overlay finishes. The doctor check is read-only: it must not start Python, TTS, ASR, MCP, or game processes.

Command-line check:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/doctor.ps1
```

## Runtime Directories

Ignored local runtime directories:

```text
backend/runtime/python/.venv
backend/runtime/asr/sherpa-qwen
backend/runtime/asr/custom
backend/tts
backend/llm
```

Tracked setup and documentation:

```text
backend/tools/python/bootstrap.ps1
backend/tools/python/requirements-stt.txt
backend/tools/python/README.md
backend/tools/asr/README.md
```

## ASR Contract

Qt streams PCM audio to:

```text
ws://<backend>/stt/stream
```

The backend STT handler expects an ASR sidecar on:

```text
127.0.0.1:6006
```

Supported STT runtime:

- sherpa-onnx Qwen3-ASR ONNX sidecar at `backend/tools/asr/sherpa_qwen_sidecar.py`.
- `sherpa_onnx`, `numpy`, and `websockets` are required in `backend/runtime/asr/sherpa-qwen/.venv`.
- Qwen3-ASR ONNX model files are required under `backend/runtime/asr/sherpa-qwen/models/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25`.
- Speaker diarization files are required under `backend/runtime/asr/sherpa-qwen/models/diarization`.
- Port `6006` must be free before backend startup. The backend does not reuse an unknown process on that port.

Model weights and lazy-pack files belong under:

```text
backend/runtime/asr/custom/
```

The sherpa-qwen bootstrap is:

```powershell
powershell -ExecutionPolicy Bypass -File backend/tools/asr/bootstrap-sherpa-qwen.ps1
```

Doctor checks `stt.engine`, `stt.sherpa-qwen-sidecar-entry`, `stt.sherpa-qwen-model`, `stt.sherpa-diarization-model`, `stt.sherpa-qwen-runtime`, and `stt.sidecar-port`.

## Verification

Stable verifier:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/verify.ps1 -Scope quick
```

The quick verifier covers doctor, API contract, and STT startup configuration tests. Use `-Scope qt` after Qt C++ or QML changes.
