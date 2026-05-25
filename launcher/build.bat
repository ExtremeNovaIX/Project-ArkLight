@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"
set "BUILD_DIR=%SCRIPT_DIR%\build"
set "DIST_DIR=%SCRIPT_DIR%\dist"

if exist "E:\QT\Tools\CMake_64\bin\cmake.exe" set "PATH=E:\QT\Tools\CMake_64\bin;%PATH%"
if exist "E:\QT\Tools\Ninja\ninja.exe" set "PATH=E:\QT\Tools\Ninja;%PATH%"
if exist "E:\QT\Tools\mingw1310_64\bin\g++.exe" set "PATH=E:\QT\Tools\mingw1310_64\bin;%PATH%"
if exist "E:\CLion 2025.1\bin\cmake\win\x64\bin\cmake.exe" set "PATH=E:\CLion 2025.1\bin\cmake\win\x64\bin;%PATH%"
if exist "E:\CLion 2025.1\bin\ninja\win\x64\ninja.exe" set "PATH=E:\CLion 2025.1\bin\ninja\win\x64;%PATH%"
if exist "E:\CLion 2025.1\bin\mingw\bin\g++.exe" set "PATH=E:\CLion 2025.1\bin\mingw\bin;%PATH%"

if exist "E:\QT\Tools\mingw1310_64\bin\g++.exe" (
    set "CC=E:\QT\Tools\mingw1310_64\bin\gcc.exe"
    set "CXX=E:\QT\Tools\mingw1310_64\bin\g++.exe"
)
if not defined CXX if exist "E:\CLion 2025.1\bin\mingw\bin\g++.exe" (
    set "CC=E:\CLion 2025.1\bin\mingw\bin\gcc.exe"
    set "CXX=E:\CLion 2025.1\bin\mingw\bin\g++.exe"
)

set "DIRECT_GXX="
if exist "E:\QT\Tools\mingw1310_64\bin\g++.exe" set "DIRECT_GXX=E:\QT\Tools\mingw1310_64\bin\g++.exe"
if not defined DIRECT_GXX if exist "E:\CLion 2025.1\bin\mingw\bin\g++.exe" set "DIRECT_GXX=E:\CLion 2025.1\bin\mingw\bin\g++.exe"

if defined DIRECT_GXX (
    if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"
    "%DIRECT_GXX%" -std=c++17 -municode -mwindows -O2 -Wall -Wextra "%SCRIPT_DIR%\src\ArklightLauncher.cpp" -o "%DIST_DIR%\Arklight.exe" -static -static-libgcc -static-libstdc++ -lws2_32 -lshell32
    if not errorlevel 1 (
        echo Launcher built:
        echo   %DIST_DIR%\Arklight.exe
        exit /b 0
    )
    echo Direct MinGW build failed, falling back to CMake...
)

where cmake >nul 2>nul
if errorlevel 1 (
    echo Could not find cmake.
    exit /b 1
)

set "GENERATOR_ARGS="
where ninja >nul 2>nul
if not errorlevel 1 set "GENERATOR_ARGS=-G Ninja"

if not exist "%BUILD_DIR%" mkdir "%BUILD_DIR%"
cmake %GENERATOR_ARGS% -S "%SCRIPT_DIR%" -B "%BUILD_DIR%"
if errorlevel 1 exit /b 1

cmake --build "%BUILD_DIR%" --config Release
if errorlevel 1 exit /b 1

if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"

set "EXE_PATH=%BUILD_DIR%\Arklight.exe"
if not exist "%EXE_PATH%" set "EXE_PATH=%BUILD_DIR%\Release\Arklight.exe"
if not exist "%EXE_PATH%" set "EXE_PATH=%BUILD_DIR%\Debug\Arklight.exe"

if not exist "%EXE_PATH%" (
    echo Launcher executable not found under %BUILD_DIR%.
    exit /b 1
)

copy /y "%EXE_PATH%" "%DIST_DIR%\Arklight.exe" >nul
echo Launcher built:
echo   %DIST_DIR%\Arklight.exe
