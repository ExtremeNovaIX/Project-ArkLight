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

echo Starting Arklight backend...
echo Selecting backend port...
for /f %%P in ('powershell -NoProfile -ExecutionPolicy Bypass -Command "$ports=@(8080)+(18080..18120); foreach($p in $ports){ $listener=$null; try { $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$p); $listener.Start(); $listener.Stop(); Write-Output $p; exit 0 } catch { if($listener){ $listener.Stop() } } }; exit 1"') do set "BACKEND_PORT=%%P"
if not defined BACKEND_PORT (
    echo Could not find a free local port for backend.
    pause
    exit /b 1
)

echo Starting Arklight backend on http://127.0.0.1:%BACKEND_PORT% ...
start "Arklight Backend" /min cmd /c ""%JAVA_EXE%" -Dfile.encoding=UTF-8 -Darclight.config.dir="%ARCLIGHT_CONFIG_DIR%" -Dassistant.rp.character-directory="%ARCLIGHT_CHARA_DIR%" -jar "backend\ArcLight-chat.jar" --server.address=127.0.0.1 --server.port=%BACKEND_PORT% >> "logs\backend.stdout.log" 2>&1"

echo Waiting for http://127.0.0.1:%BACKEND_PORT% ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$url='http://127.0.0.1:%BACKEND_PORT%/'; $deadline=(Get-Date).AddSeconds(90); while((Get-Date) -lt $deadline){ try { $r=Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 2; if($r.StatusCode -eq 200 -and $r.Content -match '<title>Arklight</title>'){ exit 0 } } catch {}; Start-Sleep -Milliseconds 500 }; exit 1"
if errorlevel 1 (
    echo Backend did not become ready. See logs\backend.stdout.log
    pause
    exit /b 1
)

start "" "http://127.0.0.1:%BACKEND_PORT%/"
echo Web UI opened. Keep the backend window running while using Arklight.
exit /b 0
