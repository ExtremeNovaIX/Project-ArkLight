@echo off
setlocal

cd /d "%~dp0"
if not exist logs mkdir logs

set "ARCLIGHT_APP_HOME=%CD%"
set "ARCLIGHT_CONFIG_DIR=%CD%\config"
set "ARCLIGHT_CHARA_DIR=%CD%\chara"
set "MCP_SERVERS_DIR=%CD%\mcp-servers"
set "MCP_REGISTRY_FILE=%CD%\config\mcp-registry.json"

set "JAVA_EXE=%CD%\runtime\bin\java.exe"
if not exist "%JAVA_EXE%" set "JAVA_EXE=java"

if not exist "backend\ArcLight-chat.jar" (
    echo backend\ArcLight-chat.jar not found.
    pause
    exit /b 1
)

if not exist "%ARCLIGHT_CHARA_DIR%" (
    echo chara directory not found: %ARCLIGHT_CHARA_DIR%
    pause
    exit /b 1
)

if not exist "qt\arklight_qt.exe" (
    echo qt\arklight_qt.exe not found.
    echo Use start-web.bat, or package Qt with QTDIR/PREBUILT_QT_DIR.
    pause
    exit /b 1
)

echo Starting Arklight backend...
start "Arklight Backend" /min cmd /c ""%JAVA_EXE%" -Dfile.encoding=UTF-8 -Darclight.config.dir="%ARCLIGHT_CONFIG_DIR%" -Dassistant.rp.character-directory="%ARCLIGHT_CHARA_DIR%" -jar "backend\ArcLight-chat.jar" --server.address=127.0.0.1 >> "logs\backend.stdout.log" 2>&1"

echo Waiting for http://127.0.0.1:8080 ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$deadline=(Get-Date).AddSeconds(90); while((Get-Date) -lt $deadline){ try { $client=[Net.Sockets.TcpClient]::new(); $iar=$client.BeginConnect('127.0.0.1',8080,$null,$null); if($iar.AsyncWaitHandle.WaitOne(500)){ $client.EndConnect($iar); $client.Close(); exit 0 }; $client.Close() } catch {}; Start-Sleep -Milliseconds 500 }; exit 1"
if errorlevel 1 (
    echo Backend did not become ready. See logs\backend.stdout.log
    pause
    exit /b 1
)

start "" "qt\arklight_qt.exe"
echo Qt frontend started. Keep the backend window running while using Arklight.
exit /b 0
