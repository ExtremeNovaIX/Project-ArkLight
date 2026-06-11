import QtQuick
import QtQuick.Layouts

Item {
    id: scene
    property real scaleFactor: 1
    property string workspaceName: "ArkLight Pioneer"
    property string operatorName: "本地"
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
    property int moteCount: 42
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

    StardustField {
        anchors.fill: parent
        scaleFactor: scene.scaleFactor
        count: scene.moteCount
        moteColor: tokens.orange
        clip: true
    }

    DiagonalGrid {
        anchors.fill: parent
        step: scene.sp(80)
        lineColor: tokens.ink
        lineAlpha: 0.085
        opacity: 0.058
    }

    RowLayout {
        anchors.fill: parent
        spacing: 0

        CharacterStage {
            Layout.preferredWidth: Math.max(scene.sp(320), scene.width * 0.4)
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

    BootOverlay {
        anchors.fill: parent
        z: 100
        scaleFactor: scene.scaleFactor
        workspaceName: scene.workspaceName
        active: scene.isBooting
        showTitle: scene.showBootTitle
    }
}
