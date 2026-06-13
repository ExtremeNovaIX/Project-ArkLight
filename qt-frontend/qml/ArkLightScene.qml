import QtQuick
import QtQuick.Layouts

Item {
    id: scene
    property real scaleFactor: 1
    property string workspaceName: "ArkLight Pioneer"
    property string operatorName: "Local"
    property string backendBaseUrl: ""
    property string characterName: ""
    property string characterImageUrl: ""
    property string activeCharacterEmotion: ""
    property var messagesModel: null
    property string userInput: ""
    property bool assistantTyping: false
    property bool sendDisabled: false
    property bool isBooting: false
    property bool showBootTitle: false
    signal openSettings()
    signal inputEdited(string value)
    signal submitMessage(string value)

    ArkLightTokens {
        id: tokens
    }

    function sp(value) {
        return Math.round(value * scaleFactor)
    }

    Rectangle {
        anchors.fill: parent
        color: tokens.blackPanel
    }

    SurfaceTexture {
        anchors.fill: parent
        scaleFactor: scene.scaleFactor
        lineColor: "#FFFFFF"
        lineAlpha: 0.018
        geometryAlpha: 0.01
    }

    Item {
        id: shell
        anchors.fill: parent
        anchors.margins: scene.sp(tokens.outerMargin)
        clip: true

        Rectangle {
            anchors.fill: parent
            radius: scene.sp(tokens.radiusFrame)
            color: tokens.paper
            border.color: tokens.whiteAlpha(0.12)
            border.width: 1
        }

        SurfaceTexture {
            anchors.fill: parent
            scaleFactor: scene.scaleFactor
            lineColor: tokens.chatGrid
            lineAlpha: 0.038
            geometryAlpha: 0.018
        }

        RowLayout {
            anchors.fill: parent
            spacing: 0

            CharacterStage {
                Layout.preferredWidth: Math.max(scene.sp(330), Math.min(shell.width * 0.39, scene.sp(560)))
                Layout.fillHeight: true
                scaleFactor: scene.scaleFactor
                workspaceName: scene.workspaceName
                characterName: scene.characterName
                characterImageUrl: scene.characterImageUrl
                activeEmotion: scene.activeCharacterEmotion
            }

            ChatSurface {
                Layout.fillWidth: true
                Layout.fillHeight: true
                scaleFactor: scene.scaleFactor
                workspaceName: scene.workspaceName
                operatorName: scene.operatorName
                backendBaseUrl: scene.backendBaseUrl
                messagesModel: scene.messagesModel
                userInput: scene.userInput
                assistantTyping: scene.assistantTyping
                sendDisabled: scene.sendDisabled
                onOpenSettings: scene.openSettings()
                onInputEdited: function(value) {
                    scene.inputEdited(value)
                }
                onSubmitMessage: function(value) {
                    scene.submitMessage(value)
                }
            }
        }
    }

    BootOverlay {
        anchors.fill: parent
        z: 100
        scaleFactor: scene.scaleFactor
        workspaceName: scene.workspaceName
        active: scene.isBooting
        showTitle: scene.showBootTitle
    }
}
