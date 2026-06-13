# Qt Frontend

This directory contains the Qt/QML desktop frontend for Arklight.

## Current Scope

- Qt 6 QML application shell
- ArkLight-themed chat scene
- backend chat API client for `POST /api/chat/send`, live chat SSE, TTS SSE, and local config pages
- local settings persistence with `QSettings`
- character directory scanning from the project-level `chara` folder
- emotion prefix parsing and portrait switching
- startup runtime doctor check through `GET /api/doctor/status`

## Build

Typical local build flow:

```bash
cmake --preset qt-mingw-debug
cmake --build --preset qt-mingw-debug
```

Project verifier wrapper:

```powershell
powershell -ExecutionPolicy Bypass -File ..\scripts\verify.ps1 -Scope qt
```

Requirements:

- Qt 6.5+
- CMake 3.21+

Windows helper:

```bat
qt-frontend\build.bat
qt-frontend\deploy.bat
qt-frontend\run.bat
```

The helper script builds into `qt-frontend\build\qt-frontend` and looks for `qt-cmake.bat` in common `C:\Qt\...` and `E:\QT\...` locations, or uses `%QTCMAKE%` if you set it explicitly.

If Qt is installed in a custom location, you can point the script at it before building:

```bat
set QTCMAKE=C:\path\to\qt-cmake.bat
qt-frontend\build.bat
```

or:

```bat
set QTDIR=C:\Qt\6.7.3\msvc2022_64
qt-frontend\build.bat
```

## Notes

- The app searches upward from the current working directory and executable directory for a `chara` folder.
- The current first-run path works best when the app is launched from the repo root or from a build directory under the repo so the shared `chara` folder can be discovered.
- A minimal sample character is included at `chara/Demo/prompt.txt` so the backend and Qt frontend have at least one valid role to start with.
- In JetBrains Rider, open this folder as a CMake project and select the `qt-mingw-debug` preset. The build runs `windeployqt` after linking so the target can be started directly from the IDE.
- If Rider fails to open the project after a bad import, close Rider and remove the local `qt-frontend\.idea` folder, then reopen `qt-frontend` itself rather than `CMakeLists.txt` or a generated build folder.
- Runtime dependency warnings come from the backend doctor API. If the dialog reports backend unavailable, start the Java backend first.
