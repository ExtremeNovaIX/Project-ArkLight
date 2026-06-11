import QtQuick
import QtQuick.Controls
import QtQuick.Window
import Arklight

ApplicationWindow {
    id: window
    width: 1320
    height: 820
    minimumWidth: Math.round(980 * uiScale)
    minimumHeight: Math.round(640 * uiScale)
    visible: true
    title: "ArkLight Pioneer"
    flags: Qt.Window
    color: tokens.paper

    property real uiScale: frontendSettings.uiScalePercent / 100
    property bool isBooting: false
    property bool showBootTitle: false
    property string userInput: ""

    ArkLightTokens {
        id: tokens
    }

    function startBootSequence() {
        bootTitleTimer.stop()
        bootDismissTimer.stop()

        if (!frontendSettings.bootAnimationEnabled) {
            showBootTitle = true
            isBooting = false
            return
        }

        showBootTitle = false
        isBooting = true
        bootTitleTimer.interval = Math.max(150, Math.min(800, Math.floor(frontendSettings.bootDurationMs * 0.18)))
        bootDismissTimer.interval = Math.max(0, frontendSettings.bootDurationMs)
        bootTitleTimer.restart()
        bootDismissTimer.restart()
    }

    Timer {
        id: bootTitleTimer
        repeat: false
        onTriggered: showBootTitle = true
    }

    Timer {
        id: bootDismissTimer
        repeat: false
        onTriggered: isBooting = false
    }

    Component.onCompleted: {
        startBootSequence()
    }

    Connections {
        target: frontendSettings
        function onSettingsChanged() {
            if (!frontendSettings.bootAnimationEnabled && isBooting) {
                isBooting = false
            }
        }
    }

    ArkLightScene {
        anchors.fill: parent
        scaleFactor: window.uiScale
        workspaceName: frontendSettings.workspaceName
        operatorName: frontendSettings.operatorName
        backendBaseUrl: frontendSettings.backendBaseUrl
        characterName: frontendSettings.characterName
        characterImageUrl: chatSession.activeCharacterImagePath
        activeCharacterEmotion: chatSession.activeEmotion
        messagesModel: chatSession.messages
        userInput: window.userInput
        assistantTyping: chatSession.busy
        sendDisabled: chatSession.busy
        isBooting: window.isBooting
        showBootTitle: window.showBootTitle
        moteCount: frontendSettings.moteCount

        onOpenSettings: {
            settingsDialog.open()
        }

        onInputEdited: function(value) {
            if (window.userInput !== value) {
                window.userInput = value
                chatSession.reportTypingActivity()
            }
        }

        onSubmitMessage: function(value) {
            const content = value.trim()
            if (content.length === 0 || chatSession.busy) {
                return
            }
            chatSession.sendMessage(content)
            window.userInput = ""
        }
    }

    SettingsCompatDialog {
        id: settingsDialog
        width: Math.min(window.width - Math.round(64 * window.uiScale), Math.round(1280 * window.uiScale))
        height: Math.min(window.height - Math.round(64 * window.uiScale), Math.round(760 * window.uiScale))
        anchors.centerIn: Overlay.overlay
        scaleFactor: window.uiScale
    }
}
