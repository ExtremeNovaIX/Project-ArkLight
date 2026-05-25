# Arklight Windows Launcher

This launcher is a small Windows executable for portable releases.

Expected release layout:

```text
Arklight-v0.1/
  Arklight-Qt.exe
  Arklight-Web.exe
  runtime/
  backend/ArcLight-chat.jar
  qt/arklight_qt.exe
  config/
  chara/
  mcp-servers/
  data/
  logs/
```

Behavior:

- Starts `backend/ArcLight-chat.jar` with `runtime/bin/java.exe` when present, otherwise falls back to `java.exe` on `PATH`.
- Sets `ARCLIGHT_CONFIG_DIR`, `MCP_SERVERS_DIR`, and `MCP_REGISTRY_FILE` based on the release root.
- Waits until `127.0.0.1:8080` accepts TCP connections.
- Starts Qt when launched as `Arklight-Qt.exe` or with `--qt`.
- Opens the browser when launched as `Arklight-Web.exe` or with `--web`.
- Stops the backend process tree when the launcher exits, but only if this launcher started it.

Build:

```bat
launcher\build.bat
```
