@echo off
setlocal

set "ROOT=%~dp0.."
for %%I in ("%ROOT%") do set "ROOT=%%~fI"

set "ARCLIGHT_APP_HOME=%ROOT%"
set "ARCLIGHT_CONFIG_DIR=%ROOT%\config"
set "ARCLIGHT_CHARA_DIR=%ROOT%\chara"
set "MCP_SERVERS_DIR=%ROOT%\mcp-servers"
set "MCP_REGISTRY_FILE=%ROOT%\config\mcp-registry.json"

if not exist "%ROOT%\backend\pom.xml" (
    echo backend\pom.xml not found.
    pause
    exit /b 1
)

if not exist "%ROOT%\frontend\arklight-frontend\package.json" (
    echo frontend\arklight-frontend\package.json not found.
    pause
    exit /b 1
)

if not exist "%ROOT%\frontend\arklight-frontend\node_modules" (
    echo node_modules not found. Run npm install in frontend\arklight-frontend first.
    pause
    exit /b 1
)

echo Starting backend on http://127.0.0.1:8080 ...
start "Arklight Backend Dev" cmd /k "cd /d "%ROOT%\backend" && mvn.cmd spring-boot:run -Dspring-boot.run.arguments=--server.address=127.0.0.1"

echo Waiting for backend...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$deadline=(Get-Date).AddSeconds(90); while((Get-Date) -lt $deadline){ try { $client=[Net.Sockets.TcpClient]::new(); $iar=$client.BeginConnect('127.0.0.1',8080,$null,$null); if($iar.AsyncWaitHandle.WaitOne(500)){ $client.EndConnect($iar); $client.Close(); exit 0 }; $client.Close() } catch {}; Start-Sleep -Milliseconds 500 }; exit 1"
if errorlevel 1 (
    echo Backend did not become ready.
    pause
    exit /b 1
)

echo Starting Vite frontend on http://127.0.0.1:3000 ...
start "Arklight Web Dev" cmd /k "cd /d "%ROOT%\frontend\arklight-frontend" && npm run dev"

echo Waiting for Vite...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$deadline=(Get-Date).AddSeconds(45); while((Get-Date) -lt $deadline){ try { $client=[Net.Sockets.TcpClient]::new(); $iar=$client.BeginConnect('127.0.0.1',3000,$null,$null); if($iar.AsyncWaitHandle.WaitOne(500)){ $client.EndConnect($iar); $client.Close(); exit 0 }; $client.Close() } catch {}; Start-Sleep -Milliseconds 500 }; exit 1"
if errorlevel 1 (
    echo Vite did not become ready.
    pause
    exit /b 1
)

start "" "http://127.0.0.1:3000"
exit /b 0
