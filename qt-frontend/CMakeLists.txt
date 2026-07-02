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
    Test
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
    src/app/ConfigCatalogController.cpp
    src/app/ConfigCatalogController.h
    src/app/config/ConfigCatalogSupport.cpp
    src/app/config/ConfigCatalogSupport.h
    src/app/DoctorController.cpp
    src/app/DoctorController.h
    src/app/FrontendSettings.cpp
    src/app/FrontendSettings.h
    src/app/MessageParser.cpp
    src/app/MessageParser.h
    src/app/SttAudioController.cpp
    src/app/SttAudioController.h
    src/app/TtsAudioController.cpp
    src/app/TtsAudioController.h
    src/app/audio/SttAudioProcessing.cpp
    src/app/audio/SttAudioProcessing.h
    src/app/tts/TtsAudioCodecSupport.cpp
    src/app/tts/TtsAudioCodecSupport.h
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
        qml/SettingsBlackToggle.qml
        qml/SettingsConfigFieldEditor.qml
        qml/SettingsConfigPanel.qml
        qml/SettingsFrontendPanel.qml
        qml/SettingsGamePanel.qml
        qml/SettingsNav.qml
        qml/SettingsNavButton.qml
        qml/SettingsSectionTitle.qml
        qml/SettingsUiButton.qml
        qml/SettingsUiCard.qml
        qml/SettingsUiCombo.qml
        qml/SettingsUiField.qml
        qml/SettingsVoicePanel.qml
        qml/StatusStrip.qml
        qml/SurfaceTexture.qml
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


enable_testing()
function(arklight_copy_runtime_dlls target_name)
    if(WIN32)
        add_custom_command(TARGET ${target_name} POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E copy_if_different
                    $<TARGET_RUNTIME_DLLS:${target_name}>
                    $<TARGET_FILE_DIR:${target_name}>
            COMMAND_EXPAND_LISTS
            VERBATIM
        )
    endif()
endfunction()

qt_add_executable(arklight_qt_tests
    tests/ConfigCatalogSupportTest.cpp
    src/app/config/ConfigCatalogSupport.cpp
    src/app/config/ConfigCatalogSupport.h
)

target_include_directories(arklight_qt_tests PRIVATE src/app)

target_link_libraries(arklight_qt_tests
    PRIVATE
        Qt6::Core
        Qt6::Test
)

arklight_copy_runtime_dlls(arklight_qt_tests)

add_test(NAME arklight_qt_tests COMMAND arklight_qt_tests)

qt_add_executable(arklight_audio_tests
    tests/AudioSupportTest.cpp
    src/app/audio/SttAudioProcessing.cpp
    src/app/audio/SttAudioProcessing.h
    src/app/tts/TtsAudioCodecSupport.cpp
    src/app/tts/TtsAudioCodecSupport.h
)

target_include_directories(arklight_audio_tests PRIVATE src/app)

target_link_libraries(arklight_audio_tests
    PRIVATE
        Qt6::Core
        Qt6::Multimedia
        Qt6::Test
)

arklight_copy_runtime_dlls(arklight_audio_tests)

add_test(NAME arklight_audio_tests COMMAND arklight_audio_tests)

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
