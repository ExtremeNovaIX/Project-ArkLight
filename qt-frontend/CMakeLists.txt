cmake_minimum_required(VERSION 3.21)

project(ArklightQtFrontend VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTORCC ON)
set(CMAKE_AUTOUIC ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(Qt6 6.5 REQUIRED COMPONENTS
    Core
    Gui
    Multimedia
    Network
    Qml
    Quick
    QuickControls2
)

qt_standard_project_setup(REQUIRES 6.5)
qt_policy(SET QTP0004 NEW)

qt_add_executable(arklight_qt
    src/main.cpp
    src/app/ChatClient.cpp
    src/app/ChatClient.h
    src/app/ChatMessageModel.cpp
    src/app/ChatMessageModel.h
    src/app/CharacterCatalog.cpp
    src/app/CharacterCatalog.h
    src/app/ChatSessionController.cpp
    src/app/ChatSessionController.h
    src/app/FrontendSettings.cpp
    src/app/FrontendSettings.h
    src/app/MessageParser.cpp
    src/app/MessageParser.h
    src/app/SttAudioController.cpp
    src/app/SttAudioController.h
    src/app/WindowsProcessAudioCapture.cpp
    src/app/WindowsProcessAudioCapture.h
)

qt_add_qml_module(arklight_qt
    URI Arklight
    VERSION 1.0
    QML_FILES
        qml/ArkLightScene.qml
        qml/ArkLightTokens.qml
        qml/BootOverlay.qml
        qml/CharacterStage.qml
        qml/ChatComposer.qml
        qml/ChatSurface.qml
        qml/DiagonalGrid.qml
        qml/IconCpu.qml
        qml/IconGear.qml
        qml/IconOrbit.qml
        qml/IconSend.qml
        qml/IconUser.qml
        qml/Main.qml
        qml/SettingsCompatDialog.qml
        qml/StardustField.qml
        qml/StatusStrip.qml
)

target_include_directories(arklight_qt PRIVATE src/app)

target_link_libraries(arklight_qt
    PRIVATE
        Qt6::Core
        Qt6::Gui
        Qt6::Multimedia
        Qt6::Network
        Qt6::Qml
        Qt6::Quick
        Qt6::QuickControls2
)

set_target_properties(arklight_qt PROPERTIES
    WIN32_EXECUTABLE TRUE
    MACOSX_BUNDLE TRUE
)

if(WIN32)
    target_link_libraries(arklight_qt PRIVATE ole32 avrt mmdevapi)

    find_program(WINDEPLOYQT_EXECUTABLE
        NAMES windeployqt
        HINTS "${Qt6_DIR}/../../../bin"
        REQUIRED
    )

    add_custom_command(TARGET arklight_qt POST_BUILD
        COMMAND "${WINDEPLOYQT_EXECUTABLE}"
                --qmldir "${CMAKE_CURRENT_SOURCE_DIR}/qml"
                "$<TARGET_FILE:arklight_qt>"
        COMMENT "Deploying Qt runtime files for local IDE runs"
        VERBATIM
    )
endif()

install(TARGETS arklight_qt
    BUNDLE DESTINATION .
    RUNTIME DESTINATION bin
)
