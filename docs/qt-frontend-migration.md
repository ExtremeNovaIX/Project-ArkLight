# Qt Frontend Notes

Qt/QML is the maintained Arklight frontend. The previous Vue/Vite frontend has been removed and should not be used as a design, packaging, or API reference.

## Current Entry Points

- C++ entry: `qt-frontend/src/main.cpp`
- QML entry: `qt-frontend/qml/Main.qml`
- Local preferences: `qt-frontend/src/app/FrontendSettings.*`
- Local YAML config editor: `qt-frontend/src/app/ConfigCatalogController.*`

## Runtime Contracts

- Qt talks to the backend through the API documented in `docs/contracts/arclight-api.openapi.json`.
- Local runtime checks come from `GET /api/doctor/status`.
- STT audio is streamed to `/stt/stream`.
- TTS audio events are consumed from `GET /api/tts/live`.
- Local YAML editing is handled in Qt; the backend no longer exposes a config editing API.

## Maintenance Notes

- Keep Qt behavior aligned with the OpenAPI contract when backend endpoints change.
- Keep YAML editing local to `ConfigCatalogController`; do not reintroduce backend config editing endpoints.
- Treat large Qt controllers as refactor candidates when changing related behavior:
  - `SttAudioController` should trend toward separate capture, WebSocket, and PCM conversion helpers.
  - `TtsAudioController` should trend toward separate stream subscription, decoding, buffering, and device output helpers.
  - `ConfigCatalogController` should trend toward separate catalog definitions, YAML store, history store, and QML facade.

## Verification

```powershell
powershell -ExecutionPolicy Bypass -File scripts/verify.ps1 -Scope quick
powershell -ExecutionPolicy Bypass -File scripts/verify.ps1 -Scope qt
```
