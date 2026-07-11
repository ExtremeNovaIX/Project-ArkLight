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
        color: tokens.paper
    }

    Item {
        id: shell
        anchors.fill: parent
        clip: true

        RowLayout {
            anchors.fill: parent
            spacing: 0

            CharacterStage {
                Layout.preferredWidth: Math.max(scene.sp(410), Math.min(shell.width * 0.45, scene.sp(640)))
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
