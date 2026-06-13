@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"
set "SOURCE_DIR=%SCRIPT_DIR%"
for %%I in ("%SCRIPT_DIR%\build\qt-frontend") do set "BUILD_DIR=%%~fI"

set "QT_CMAKE="
set "CMAKE_BIN="
set "QT_TOOLS_CMAKE_BIN="
set "QT_TOOLS_NINJA_BIN="
set "QT_TOOLS_MINGW_BIN="

if defined QTCMAKE (
    set "QT_CMAKE=%QTCMAKE%"
)

if not defined QT_CMAKE if defined QTDIR if exist "%QTDIR%\bin\qt-cmake.bat" set "QT_CMAKE=%QTDIR%\bin\qt-cmake.bat"
if not defined QT_CMAKE if defined QT_ROOT if exist "%QT_ROOT%\bin\qt-cmake.bat" set "QT_CMAKE=%QT_ROOT%\bin\qt-cmake.bat"
if not defined QT_CMAKE if defined CMAKE_PREFIX_PATH if exist "%CMAKE_PREFIX_PATH%\bin\qt-cmake.bat" set "QT_CMAKE=%CMAKE_PREFIX_PATH%\bin\qt-cmake.bat"

if not defined QT_CMAKE if exist "C:\Qt\6.7.3\msvc2022_64\bin\qt-cmake.bat" set "QT_CMAKE=C:\Qt\6.7.3\msvc2022_64\bin\qt-cmake.bat"
if not defined QT_CMAKE if exist "C:\Qt\6.7.2\msvc2022_64\bin\qt-cmake.bat" set "QT_CMAKE=C:\Qt\6.7.2\msvc2022_64\bin\qt-cmake.bat"
if not defined QT_CMAKE if exist "C:\Qt\6.6.3\msvc2022_64\bin\qt-cmake.bat" set "QT_CMAKE=C:\Qt\6.6.3\msvc2022_64\bin\qt-cmake.bat"
if not defined QT_CMAKE if exist "C:\Qt\6.5.3\msvc2022_64\bin\qt-cmake.bat" set "QT_CMAKE=C:\Qt\6.5.3\msvc2022_64\bin\qt-cmake.bat"
if not defined QT_CMAKE if exist "C:\Qt\6.7.3\mingw_64\bin\qt-cmake.bat" set "QT_CMAKE=C:\Qt\6.7.3\mingw_64\bin\qt-cmake.bat"
if not defined QT_CMAKE if exist "C:\Qt\6.7.2\mingw_64\bin\qt-cmake.bat" set "QT_CMAKE=C:\Qt\6.7.2\mingw_64\bin\qt-cmake.bat"
if not defined QT_CMAKE if exist "C:\Qt\6.6.3\mingw_64\bin\qt-cmake.bat" set "QT_CMAKE=C:\Qt\6.6.3\mingw_64\bin\qt-cmake.bat"
if not defined QT_CMAKE if exist "C:\Qt\6.5.3\mingw_64\bin\qt-cmake.bat" set "QT_CMAKE=C:\Qt\6.5.3\mingw_64\bin\qt-cmake.bat"
if not defined QT_CMAKE if exist "C:\Qt\6.11.0\mingw_64\bin\qt-cmake.bat" set "QT_CMAKE=C:\Qt\6.11.0\mingw_64\bin\qt-cmake.bat"
if not defined QT_CMAKE if exist "C:\Qt\6.11.0\msvc2022_64\bin\qt-cmake.bat" set "QT_CMAKE=C:\Qt\6.11.0\msvc2022_64\bin\qt-cmake.bat"
if not defined QT_CMAKE if exist "E:\QT\6.11.0\mingw_64\bin\qt-cmake.bat" set "QT_CMAKE=E:\QT\6.11.0\mingw_64\bin\qt-cmake.bat"
if not defined QT_CMAKE if exist "E:\QT\6.11.0\msvc2022_64\bin\qt-cmake.bat" set "QT_CMAKE=E:\QT\6.11.0\msvc2022_64\bin\qt-cmake.bat"

if not defined QT_CMAKE (
    where qt-cmake >nul 2>nul && set "QT_CMAKE=qt-cmake"
)

if not defined QT_CMAKE (
    echo Could not find qt-cmake.
    echo.
    echo Supported ways to provide it:
    echo   1. set QTCMAKE=C:\path\to\qt-cmake.bat
    echo   2. set QTDIR=C:\Qt\6.x.x\msvc2022_64
    echo   3. set QT_ROOT=C:\Qt\6.x.x\mingw_64
    echo.
    echo You also need a working Qt 6.5+ installation that includes qt-cmake.
    exit /b 1
)

if exist "C:\Qt\Tools\CMake_64\bin\cmake.exe" set "QT_TOOLS_CMAKE_BIN=C:\Qt\Tools\CMake_64\bin"
if exist "C:\Qt\Tools\Ninja\ninja.exe" set "QT_TOOLS_NINJA_BIN=C:\Qt\Tools\Ninja"
if exist "C:\Qt\Tools\mingw1310_64\bin\g++.exe" set "QT_TOOLS_MINGW_BIN=C:\Qt\Tools\mingw1310_64\bin"
if exist "E:\QT\Tools\CMake_64\bin\cmake.exe" set "QT_TOOLS_CMAKE_BIN=E:\QT\Tools\CMake_64\bin"
if exist "E:\QT\Tools\Ninja\ninja.exe" set "QT_TOOLS_NINJA_BIN=E:\QT\Tools\Ninja"
if exist "E:\QT\Tools\mingw1310_64\bin\g++.exe" set "QT_TOOLS_MINGW_BIN=E:\QT\Tools\mingw1310_64\bin"

if defined QT_TOOLS_CMAKE_BIN set "PATH=%QT_TOOLS_CMAKE_BIN%;%PATH%"
if defined QT_TOOLS_NINJA_BIN set "PATH=%QT_TOOLS_NINJA_BIN%;%PATH%"
if defined QT_TOOLS_MINGW_BIN set "PATH=%QT_TOOLS_MINGW_BIN%;%PATH%"

if defined QT_TOOLS_MINGW_BIN (
    set "CMAKE_C_COMPILER=%QT_TOOLS_MINGW_BIN%\gcc.exe"
    set "CMAKE_CXX_COMPILER=%QT_TOOLS_MINGW_BIN%\g++.exe"
)

if not exist "%BUILD_DIR%" mkdir "%BUILD_DIR%"

if exist "%BUILD_DIR%\CMakeCache.txt" (
    findstr /C:"CMAKE_GENERATOR:INTERNAL=Ninja" "%BUILD_DIR%\CMakeCache.txt" >nul 2>nul
    if errorlevel 1 (
        echo Removing incompatible build cache...
        rmdir /s /q "%BUILD_DIR%"
        mkdir "%BUILD_DIR%"
    )
)

if exist "%BUILD_DIR%\CMakeCache.txt" (
    findstr /C:"CMAKE_GENERATOR:INTERNAL=Visual Studio" "%BUILD_DIR%\CMakeCache.txt" >nul 2>nul
    if not errorlevel 1 (
        echo Removing incompatible Visual Studio build cache...
        rmdir /s /q "%BUILD_DIR%"
        mkdir "%BUILD_DIR%"
    )
)

call "%QT_CMAKE%" -G Ninja -S "%SOURCE_DIR%" -B "%BUILD_DIR%" -DCMAKE_C_COMPILER="%CMAKE_C_COMPILER%" -DCMAKE_CXX_COMPILER="%CMAKE_CXX_COMPILER%"
if errorlevel 1 exit /b 1

call "%QT_CMAKE%" --build "%BUILD_DIR%"
if errorlevel 1 exit /b 1

echo Build completed. Run:
echo   "%BUILD_DIR%\Debug\arklight_qt.exe"
echo or
echo   "%BUILD_DIR%\arklight_qt.exe"
