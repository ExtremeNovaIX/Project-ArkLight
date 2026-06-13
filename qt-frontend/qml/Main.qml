import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
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
    color: tokens.blackPanel

    property real uiScale: frontendSettings.uiScalePercent / 100
    property bool isBooting: frontendSettings.bootAnimationEnabled
    property bool showBootTitle: false
    property bool doctorDialogDismissed: false
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

    function maybeOpenDoctorDialog() {
        if (isBooting || doctorDialogDismissed || runtimeDoctor.checking || !runtimeDoctor.hasIssues || doctorDialog.opened) {
            return
        }
        doctorDialog.open()
    }

    Timer {
        id: bootTitleTimer
        repeat: false
        onTriggered: showBootTitle = true
    }

    Timer {
        id: bootDismissTimer
        repeat: false
        onTriggered: {
            isBooting = false
            maybeOpenDoctorDialog()
        }
    }

    Component.onCompleted: {
        startBootSequence()
        runtimeDoctor.run(frontendSettings.backendBaseUrl)
    }

    Connections {
        target: frontendSettings
        function onSettingsChanged() {
            if (!frontendSettings.bootAnimationEnabled && isBooting) {
                isBooting = false
            }
        }
    }

    Connections {
        target: runtimeDoctor
        function onStatusChanged() {
            maybeOpenDoctorDialog()
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
        onOpenSettings: {
            settingsDialog.open()
        }

        onInputEdited: function(value) {
            if (window.userInput !== value) {
                window.userInput = value
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

    Dialog {
        id: doctorDialog
        width: Math.min(window.width - Math.round(80 * window.uiScale), Math.round(760 * window.uiScale))
        height: Math.min(window.height - Math.round(96 * window.uiScale), Math.round(560 * window.uiScale))
        anchors.centerIn: Overlay.overlay
        modal: true
        title: "运行时检查"
        standardButtons: Dialog.Ok
        closePolicy: Popup.CloseOnEscape
        onAccepted: doctorDialogDismissed = true
        onRejected: doctorDialogDismissed = true

        background: Rectangle {
            radius: Math.round(tokens.radiusControl * window.uiScale)
            color: tokens.paperLight
            border.color: tokens.blackAlpha(0.36)
            border.width: 1
        }

        header: Rectangle {
            implicitHeight: Math.round(58 * window.uiScale)
            color: tokens.blackPanel
            radius: Math.round(tokens.radiusControl * window.uiScale)

            Rectangle {
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.bottom: parent.bottom
                height: Math.round(tokens.radiusControl * window.uiScale)
                color: tokens.blackPanel
            }

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: Math.round(22 * window.uiScale)
                anchors.rightMargin: Math.round(22 * window.uiScale)
                spacing: Math.round(12 * window.uiScale)

                Rectangle {
                    Layout.preferredWidth: Math.round(10 * window.uiScale)
                    Layout.preferredHeight: Math.round(24 * window.uiScale)
                    radius: Math.round(2 * window.uiScale)
                    color: runtimeDoctor.status === "ERROR" ? tokens.statusRedLight : tokens.orange
                }

                Text {
                    Layout.fillWidth: true
                    text: runtimeDoctor.summary
                    color: tokens.paperLight
                    font.family: tokens.sansFont
                    font.pixelSize: Math.round(17 * window.uiScale)
                    font.weight: Font.DemiBold
                    elide: Text.ElideRight
                }

                Text {
                    text: runtimeDoctor.status
                    color: tokens.orange
                    font.family: tokens.monoFont
                    font.pixelSize: Math.round(12 * window.uiScale)
                }
            }
        }

        contentItem: ScrollView {
            clip: true
            contentWidth: availableWidth

            ColumnLayout {
                width: doctorDialog.availableWidth
                spacing: Math.round(10 * window.uiScale)

                Text {
                    Layout.fillWidth: true
                    text: "以下项目不会阻止界面启动，但会影响语音、TTS 或本地配置体验。"
                    wrapMode: Text.WordWrap
                    color: tokens.inkAlpha(0.72)
                    font.family: tokens.sansFont
                    font.pixelSize: Math.round(13 * window.uiScale)
                }

                Repeater {
                    model: runtimeDoctor.issues

                    Rectangle {
                        required property var modelData
                        Layout.fillWidth: true
                        implicitHeight: issueColumn.implicitHeight + Math.round(22 * window.uiScale)
                        radius: Math.round(tokens.radiusControl * window.uiScale)
                        color: modelData.status === "ERROR" ? Qt.rgba(143 / 255, 42 / 255, 31 / 255, 0.08) : Qt.rgba(232 / 255, 93 / 255, 4 / 255, 0.07)
                        border.color: modelData.status === "ERROR" ? tokens.statusRedLight : tokens.orangeAlpha(0.45)
                        border.width: 1

                        ColumnLayout {
                            id: issueColumn
                            anchors.left: parent.left
                            anchors.right: parent.right
                            anchors.verticalCenter: parent.verticalCenter
                            anchors.leftMargin: Math.round(14 * window.uiScale)
                            anchors.rightMargin: Math.round(14 * window.uiScale)
                            spacing: Math.round(4 * window.uiScale)

                            Text {
                                Layout.fillWidth: true
                                text: modelData.label + "  /  " + modelData.status
                                color: tokens.ink
                                font.family: tokens.sansFont
                                font.pixelSize: Math.round(14 * window.uiScale)
                                font.weight: Font.DemiBold
                                elide: Text.ElideRight
                            }

                            Text {
                                Layout.fillWidth: true
                                text: modelData.detail
                                wrapMode: Text.WordWrap
                                color: tokens.inkAlpha(0.75)
                                font.family: tokens.sansFont
                                font.pixelSize: Math.round(12 * window.uiScale)
                            }

                            Text {
                                Layout.fillWidth: true
                                visible: modelData.action && modelData.action.length > 0
                                text: modelData.action
                                wrapMode: Text.WordWrap
                                color: tokens.orangeDark
                                font.family: tokens.sansFont
                                font.pixelSize: Math.round(12 * window.uiScale)
                            }
                        }
                    }
                }
            }
        }
    }
}
