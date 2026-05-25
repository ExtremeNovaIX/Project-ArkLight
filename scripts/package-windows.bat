@echo off
setlocal EnableExtensions

set "ROOT=%~dp0.."
for %%I in ("%ROOT%") do set "ROOT=%%~fI"
if not defined ARCLIGHT_PACKAGE_VERSION set "ARCLIGHT_PACKAGE_VERSION=0.1"
set "VERSION=%ARCLIGHT_PACKAGE_VERSION%"
if defined ARCLIGHT_RELEASE_DIR (
    set "RELEASE_DIR=%ARCLIGHT_RELEASE_DIR%"
) else (
    set "RELEASE_DIR=%ROOT%\dist\Arklight-v%VERSION%"
)
set "WORK_DIR=%ROOT%\dist\package-work"

echo Packaging Arklight v%VERSION%
echo Root:
echo   %ROOT%
echo Release:
echo   %RELEASE_DIR%

if exist "%RELEASE_DIR%" (
    rmdir /s /q "%RELEASE_DIR%" || (
        echo Failed to clean release directory:
        echo   %RELEASE_DIR%
        echo Close running Arklight backend/launcher windows that are using this directory, then run again.
        exit /b 1
    )
)
if exist "%WORK_DIR%" rmdir /s /q "%WORK_DIR%"
mkdir "%RELEASE_DIR%" || exit /b 1
mkdir "%RELEASE_DIR%\backend" || exit /b 1
mkdir "%RELEASE_DIR%\qt" || exit /b 1
mkdir "%RELEASE_DIR%\config" || exit /b 1
mkdir "%RELEASE_DIR%\data" || exit /b 1
mkdir "%RELEASE_DIR%\logs" || exit /b 1
mkdir "%WORK_DIR%\npm-cache" || exit /b 1
set "NPM_CONFIG_CACHE=%WORK_DIR%\npm-cache"

echo.
echo [1/5] Building web frontend...
if "%SKIP_WEB_BUILD%"=="1" (
    if not exist "%ROOT%\frontend\arklight-frontend\dist\index.html" (
        echo SKIP_WEB_BUILD=1 but frontend\arklight-frontend\dist\index.html does not exist.
        exit /b 1
    )
    echo SKIP_WEB_BUILD=1, using existing frontend\arklight-frontend\dist.
) else (
    pushd "%ROOT%\frontend\arklight-frontend" || exit /b 1
    if "%FORCE_NPM_CI%"=="1" (
        call npm ci || exit /b 1
    ) else (
        if exist node_modules (
            echo node_modules exists, skipping npm ci. Set FORCE_NPM_CI=1 to reinstall.
        ) else (
            call npm ci || exit /b 1
        )
    )
    call npm run lint || exit /b 1
    call npm run build || exit /b 1
    popd
)

echo.
echo [2/5] Building backend jar...
pushd "%ROOT%\backend" || exit /b 1
call mvn.cmd -q -DskipTests package || exit /b 1
popd

set "BACKEND_JAR="
for %%F in ("%ROOT%\backend\target\ArcLight-chat-*.jar") do (
    if /I not "%%~xF"==".original" set "BACKEND_JAR=%%~fF"
)
if not defined BACKEND_JAR (
    echo Could not find backend jar under backend\target.
    exit /b 1
)
copy /y "%BACKEND_JAR%" "%RELEASE_DIR%\backend\ArcLight-chat.jar" >nul || exit /b 1

echo.
echo [3/5] Embedding web frontend into packaged backend jar...
where jar >nul 2>nul
if errorlevel 1 (
    echo Could not find jar.exe. Run this script with a JDK on PATH.
    exit /b 1
)
mkdir "%WORK_DIR%\web-static\BOOT-INF\classes\static\chat-ui" || exit /b 1
call :mirror "%ROOT%\frontend\arklight-frontend\dist" "%WORK_DIR%\web-static\BOOT-INF\classes\static\chat-ui" || exit /b 1
powershell -NoProfile -ExecutionPolicy Bypass -Command "$p = '%WORK_DIR%\web-static\BOOT-INF\classes\static\chat-ui\index.html'; if (Test-Path $p) { $s = Get-Content -LiteralPath $p -Raw; $s = $s -replace 'src=\"/assets/', 'src=\"/chat-ui/assets/'; $s = $s -replace 'href=\"/assets/', 'href=\"/chat-ui/assets/'; Set-Content -LiteralPath $p -Value $s -Encoding UTF8 }"
if errorlevel 1 exit /b 1
pushd "%WORK_DIR%\web-static" || exit /b 1
jar uf "%RELEASE_DIR%\backend\ArcLight-chat.jar" BOOT-INF || exit /b 1
popd

echo.
echo [4/5] Copying runtime data and building launcher...
call :mirror "%ROOT%\config" "%RELEASE_DIR%\config" || exit /b 1
call :mirror "%ROOT%\chara" "%RELEASE_DIR%\chara" || exit /b 1
if exist "%ROOT%\mcp-servers" (
    call :mirror "%ROOT%\mcp-servers" "%RELEASE_DIR%\mcp-servers" || exit /b 1
)
copy /y "%ROOT%\packaging\windows\start-web.bat" "%RELEASE_DIR%\start-web.bat" >nul || exit /b 1
copy /y "%ROOT%\packaging\windows\start-qt.bat" "%RELEASE_DIR%\start-qt.bat" >nul || exit /b 1

if not "%SKIP_RUNTIME%"=="1" (
    if defined JAVA_HOME (
        if exist "%JAVA_HOME%\bin\java.exe" (
            echo Copying Java runtime from JAVA_HOME...
            call :mirror "%JAVA_HOME%" "%RELEASE_DIR%\runtime" || exit /b 1
        )
    )
)

call "%ROOT%\launcher\build.bat" || exit /b 1
copy /y "%ROOT%\launcher\dist\Arklight.exe" "%RELEASE_DIR%\Arklight-Web.exe" >nul || exit /b 1

set "SHOULD_PACKAGE_QT="
if "%SKIP_QT%"=="1" (
    echo SKIP_QT=1, skipping Qt frontend packaging.
) else if "%PACKAGE_QT%"=="1" (
    set "SHOULD_PACKAGE_QT=1"
) else if defined PREBUILT_QT_DIR (
    set "SHOULD_PACKAGE_QT=1"
) else if defined QTDIR (
    set "SHOULD_PACKAGE_QT=1"
) else if defined QTCMAKE (
    set "SHOULD_PACKAGE_QT=1"
) else (
    echo Qt SDK was not configured. Skipping Qt packaging. Set PACKAGE_QT=1 or QTDIR/QTCMAKE/PREBUILT_QT_DIR to include it.
)

if "%SHOULD_PACKAGE_QT%"=="1" (
    if defined PREBUILT_QT_DIR (
        if not exist "%PREBUILT_QT_DIR%\arklight_qt.exe" (
            echo PREBUILT_QT_DIR does not contain arklight_qt.exe:
            echo   %PREBUILT_QT_DIR%
            exit /b 1
        )
        call :mirror "%PREBUILT_QT_DIR%" "%RELEASE_DIR%\qt" || exit /b 1
    ) else (
        call "%ROOT%\qt-frontend\build.bat" || (
            echo.
            echo Qt frontend build failed.
            echo Install Qt 6.5+ or provide one of:
            echo   set QTDIR=C:\Qt\6.x.x\mingw_64
            echo   set QTCMAKE=C:\Qt\6.x.x\mingw_64\bin\qt-cmake.bat
            echo   set PREBUILT_QT_DIR=E:\path\to\deployed\qt
            echo   set SKIP_QT=1
            if "%PACKAGE_QT%"=="1" exit /b 1
            echo Continuing with Web-only package.
            goto :after_qt
        )
        call "%ROOT%\qt-frontend\deploy.bat" || exit /b 1
        call :mirror "%ROOT%\qt-frontend\build\qt-frontend" "%RELEASE_DIR%\qt" || exit /b 1
    )
    copy /y "%ROOT%\launcher\dist\Arklight.exe" "%RELEASE_DIR%\Arklight-Qt.exe" >nul || exit /b 1
)

:after_qt
echo.
(
    echo Arklight v%VERSION%
    echo.
    echo Double click Arklight-Qt.exe to start backend and Qt frontend, if Qt was packaged.
    echo Double click Arklight-Web.exe to start backend and open the packaged web UI on an available local port.
    echo Double click start-web.bat if you prefer a plain one-click script.
    echo Double click start-qt.bat if Qt was packaged and you prefer a plain one-click script.
    echo Logs are written to the logs directory.
    echo Config files are loaded from the config directory.
) > "%RELEASE_DIR%\README.txt"

echo.
echo Package completed:
echo   %RELEASE_DIR%
if exist "%WORK_DIR%" rmdir /s /q "%WORK_DIR%"
exit /b 0

:mirror
robocopy "%~1" "%~2" /MIR /NFL /NDL /NJH /NJS /NP
if %ERRORLEVEL% GEQ 8 exit /b %ERRORLEVEL%
exit /b 0
