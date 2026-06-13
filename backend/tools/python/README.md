# Arklight Python Runtime

This directory contains the tracked bootstrap files for the shared lightweight Python runtime used by small backend tools.

The runtime itself is local machine state and is intentionally ignored by git:

```text
backend/runtime/python/.venv
```

## Initialize

Run from the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File backend/tools/python/bootstrap.ps1
```

The script installs or reuses `uv`, downloads managed Python `3.10.11`, creates `backend/runtime/python/.venv`, installs `requirements-stt.txt`, and verifies the shared imports.
The uv cache and managed Python install directory are also placed under `backend/runtime/python` so a broken user-level uv cache does not affect this project.
If project-local uv managed Python installation is blocked by local Windows permissions, the script records `backend/runtime/python/uv-python/.project-install-unavailable`, uses uv's default managed Python install directory, and still creates the venv under the project runtime directory. If uv cannot install Python anywhere, it falls back to an existing Python `3.10`-`3.12` interpreter.
If uv's venv/cache operations are blocked, the script records `backend/runtime/python/.uv-venv-unavailable`, falls back to standard `python -m venv --clear`, and installs dependencies with the venv's `pip`.

## ASR Runtime Boundary

ASR does not use this shared runtime. The default sherpa-qwen ASR engine uses its own dedicated runtime and model directory:

```text
backend/runtime/asr/sherpa-qwen/.venv
backend/runtime/asr/sherpa-qwen/models
```

Initialize it with:

```powershell
powershell -ExecutionPolicy Bypass -File backend/tools/asr/bootstrap-sherpa-qwen.ps1
```

External ASR packages, model weights, and lazy-pack files should stay in the ignored ASR bundle slot:

```text
backend/runtime/asr/custom/
```
